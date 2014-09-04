/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.yarn

import java.io.File
import java.net.{InetAddress, UnknownHostException, URI, URISyntaxException}

import scala.collection.JavaConversions._
import scala.collection.mutable.{HashMap, ListBuffer, Map}
import scala.util.{Try, Success, Failure}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.mapred.Master
import org.apache.hadoop.mapreduce.MRJobConfig
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.util.StringUtils
import org.apache.hadoop.yarn.api._
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment
import org.apache.hadoop.yarn.api.protocolrecords._
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.util.Records
import org.apache.spark.{SparkException, Logging, SparkConf, SparkContext}

/**
 * The entry point (starting in Client#main() and Client#run()) for launching Spark on YARN. The
 * Client submits an application to the YARN ResourceManager.
 *
 * Depending on the deployment mode this will launch one of two application master classes:
 * 1. In cluster mode, it will launch an [[org.apache.spark.deploy.yarn.ApplicationMaster]]
 *      which launches a driver program inside of the cluster.
 * 2. In client mode, it will launch an [[org.apache.spark.deploy.yarn.ExecutorLauncher]] to
 *      request executors on behalf of a driver running outside of the cluster.
 */
trait ClientBase extends Logging {
  val args: ClientArguments
  val conf: Configuration
  val sparkConf: SparkConf
  val yarnConf: YarnConfiguration
  val credentials = UserGroupInformation.getCurrentUser().getCredentials()
  private val SPARK_STAGING: String = ".sparkStaging"
  private val distCacheMgr = new ClientDistributedCacheManager()

  // Staging directory is private! -> rwx--------
  val STAGING_DIR_PERMISSION: FsPermission =
    FsPermission.createImmutable(Integer.parseInt("700", 8).toShort)
  // App files are world-wide readable and owner writable -> rw-r--r--
  val APP_FILE_PERMISSION: FsPermission =
    FsPermission.createImmutable(Integer.parseInt("644", 8).toShort)

  // TODO(harvey): This could just go in ClientArguments.
  def validateArgs() = {
    Map(
      ((args.userJar == null && args.amClass == classOf[ApplicationMaster].getName) ->
          "Error: You must specify a user jar when running in standalone mode!"),
      (args.userClass == null) -> "Error: You must specify a user class!",
      (args.numExecutors <= 0) -> "Error: You must specify at least 1 executor!",
      (args.amMemory <= YarnAllocationHandler.MEMORY_OVERHEAD) -> ("Error: AM memory size must be" +
        "greater than: " + YarnAllocationHandler.MEMORY_OVERHEAD),
      (args.executorMemory <= YarnAllocationHandler.MEMORY_OVERHEAD) -> ("Error: Executor memory size" +
        "must be greater than: " + YarnAllocationHandler.MEMORY_OVERHEAD.toString)
    ).foreach { case(cond, errStr) =>
      if (cond) {
        logError(errStr)
        throw new IllegalArgumentException(args.getUsageMessage())
      }
    }
  }

  def getAppStagingDir(appId: ApplicationId): String = {
    SPARK_STAGING + Path.SEPARATOR + appId.toString() + Path.SEPARATOR
  }

  def verifyClusterResources(app: GetNewApplicationResponse) = {
    val maxMem = app.getMaximumResourceCapability().getMemory()
    logInfo("Max mem capabililty of a single resource in this cluster " + maxMem)

    // If we have requested more then the clusters max for a single resource then exit.
    if (args.executorMemory > maxMem) {
      val errorMessage =
        "Required executor memory (%d MB), is above the max threshold (%d MB) of this cluster."
          .format(args.executorMemory, maxMem)

      logError(errorMessage)
      throw new IllegalArgumentException(errorMessage)
    }
    val amMem = args.amMemory + YarnAllocationHandler.MEMORY_OVERHEAD
    if (amMem > maxMem) {

      val errorMessage = "Required AM memory (%d) is above the max threshold (%d) of this cluster."
        .format(args.amMemory, maxMem)
      logError(errorMessage)
      throw new IllegalArgumentException(errorMessage)
    }

    // We could add checks to make sure the entire cluster has enough resources but that involves
    // getting all the node reports and computing ourselves.
  }

  /** See if two file systems are the same or not. */
  private def compareFs(srcFs: FileSystem, destFs: FileSystem): Boolean = {
    val srcUri = srcFs.getUri()
    val dstUri = destFs.getUri()
    if (srcUri.getScheme() == null) {
      return false
    }
    if (!srcUri.getScheme().equals(dstUri.getScheme())) {
      return false
    }
    var srcHost = srcUri.getHost()
    var dstHost = dstUri.getHost()
    if ((srcHost != null) && (dstHost != null)) {
      try {
        srcHost = InetAddress.getByName(srcHost).getCanonicalHostName()
        dstHost = InetAddress.getByName(dstHost).getCanonicalHostName()
      } catch {
        case e: UnknownHostException =>
          return false
      }
      if (!srcHost.equals(dstHost)) {
        return false
      }
    } else if (srcHost == null && dstHost != null) {
      return false
    } else if (srcHost != null && dstHost == null) {
      return false
    }
    if (srcUri.getPort() != dstUri.getPort()) {
      false
    } else {
      true
    }
  }

  /** Copy the file into HDFS if needed. */
  private def copyRemoteFile(
      dstDir: Path,
      originalPath: Path,
      replication: Short,
      setPerms: Boolean = false): Path = {
    val fs = FileSystem.get(conf)
    val remoteFs = originalPath.getFileSystem(conf)
    var newPath = originalPath
    if (! compareFs(remoteFs, fs)) {
      newPath = new Path(dstDir, originalPath.getName())
      logInfo("Uploading " + originalPath + " to " + newPath)
      FileUtil.copy(remoteFs, originalPath, fs, newPath, false, conf)
      fs.setReplication(newPath, replication)
      if (setPerms) fs.setPermission(newPath, new FsPermission(APP_FILE_PERMISSION))
    }
    // Resolve any symlinks in the URI path so using a "current" symlink to point to a specific
    // version shows the specific version in the distributed cache configuration
    val qualPath = fs.makeQualified(newPath)
    val fc = FileContext.getFileContext(qualPath.toUri(), conf)
    val destPath = fc.resolvePath(qualPath)
    destPath
  }

  private def qualifyForLocal(localURI: URI): Path = {
    var qualifiedURI = localURI
    // If not specified, assume these are in the local filesystem to keep behavior like Hadoop
    if (qualifiedURI.getScheme() == null) {
      qualifiedURI = new URI(FileSystem.getLocal(conf).makeQualified(new Path(qualifiedURI)).toString)
    }
    new Path(qualifiedURI)
  }

  def prepareLocalResources(appStagingDir: String): HashMap[String, LocalResource] = {
    logInfo("Preparing Local resources")
    // Upload Spark and the application JAR to the remote file system if necessary. Add them as
    // local resources to the application master.
    val fs = FileSystem.get(conf)

    val delegTokenRenewer = Master.getMasterPrincipal(conf)
    if (UserGroupInformation.isSecurityEnabled()) {
      if (delegTokenRenewer == null || delegTokenRenewer.length() == 0) {
        val errorMessage = "Can't get Master Kerberos principal for use as renewer"
        logError(errorMessage)
        throw new SparkException(errorMessage)
      }
    }
    val dst = new Path(fs.getHomeDirectory(), appStagingDir)
    val replication = sparkConf.getInt("spark.yarn.submit.file.replication", 3).toShort

    if (UserGroupInformation.isSecurityEnabled()) {
      val dstFs = dst.getFileSystem(conf)
      dstFs.addDelegationTokens(delegTokenRenewer, credentials)
    }

    val localResources = HashMap[String, LocalResource]()
    FileSystem.mkdirs(fs, dst, new FsPermission(STAGING_DIR_PERMISSION))

    val statCache: Map[URI, FileStatus] = HashMap[URI, FileStatus]()

    Map(
      ClientBase.SPARK_JAR -> ClientBase.getSparkJar, ClientBase.APP_JAR -> args.userJar,
      ClientBase.LOG4J_PROP -> System.getenv(ClientBase.LOG4J_CONF_ENV_KEY)
    ).foreach { case(destName, _localPath) =>
      val localPath: String = if (_localPath != null) _localPath.trim() else ""
      if (! localPath.isEmpty()) {
        val localURI = new URI(localPath)
        if (!ClientBase.LOCAL_SCHEME.equals(localURI.getScheme())) {
          val setPermissions = if (destName.equals(ClientBase.APP_JAR)) true else false
          val destPath = copyRemoteFile(dst, qualifyForLocal(localURI), replication, setPermissions)
          distCacheMgr.addResource(fs, conf, destPath, localResources, LocalResourceType.FILE,
            destName, statCache)
        }
      }
    }

    val cachedSecondaryJarLinks = ListBuffer.empty[String]
    val fileLists = List( (args.addJars, LocalResourceType.FILE, true),
      (args.files, LocalResourceType.FILE, false),
      (args.archives, LocalResourceType.ARCHIVE, false) )
    fileLists.foreach { case (flist, resType, addToClasspath) =>
      if (flist != null && !flist.isEmpty()) {
        flist.split(',').foreach { case file: String =>
          val localURI = new URI(file.trim())
          if (!ClientBase.LOCAL_SCHEME.equals(localURI.getScheme())) {
            val localPath = new Path(localURI)
            val linkname = Option(localURI.getFragment()).getOrElse(localPath.getName())
            val destPath = copyRemoteFile(dst, localPath, replication)
            distCacheMgr.addResource(fs, conf, destPath, localResources, resType,
              linkname, statCache)
            if (addToClasspath) {
              cachedSecondaryJarLinks += linkname
            }
          }
        }
      }
    }
    sparkConf.set(ClientBase.CONF_SPARK_YARN_SECONDARY_JARS, cachedSecondaryJarLinks.mkString(","))

    UserGroupInformation.getCurrentUser().addCredentials(credentials)
    localResources
  }

  def setupLaunchEnv(
      localResources: HashMap[String, LocalResource],
      stagingDir: String): HashMap[String, String] = {
    logInfo("Setting up the launch environment")

    val env = new HashMap[String, String]()

    val extraCp = sparkConf.getOption("spark.driver.extraClassPath")
    val log4jConf = System.getenv(ClientBase.LOG4J_CONF_ENV_KEY)
    ClientBase.populateClasspath(yarnConf, sparkConf, log4jConf, env, extraCp)
    env("SPARK_YARN_MODE") = "true"
    env("SPARK_YARN_STAGING_DIR") = stagingDir
    env("SPARK_USER") = UserGroupInformation.getCurrentUser().getShortUserName()
    if (log4jConf != null) {
      env(ClientBase.LOG4J_CONF_ENV_KEY) = log4jConf
    }

    // Set the environment variables to be passed on to the executors.
    distCacheMgr.setDistFilesEnv(env)
    distCacheMgr.setDistArchivesEnv(env)

    sys.env.get("SPARK_YARN_USER_ENV").foreach { userEnvs =>
      // Allow users to specify some environment variables.
      YarnSparkHadoopUtil.setEnvFromInputString(env, userEnvs, File.pathSeparator)

      // Pass SPARK_YARN_USER_ENV itself to the AM so it can use it to set up executor environments.
      env("SPARK_YARN_USER_ENV") = userEnvs
    }

    env
  }

  def userArgsToString(clientArgs: ClientArguments): String = {
    val prefix = " --args "
    val args = clientArgs.userArgs
    val retval = new StringBuilder()
    for (arg <- args) {
      retval.append(prefix).append(" '").append(arg).append("' ")
    }
    retval.toString
  }

  def calculateAMMemory(newApp: GetNewApplicationResponse): Int

  def setupSecurityToken(amContainer: ContainerLaunchContext)

  def createContainerLaunchContext(
        newApp: GetNewApplicationResponse,
        localResources: HashMap[String, LocalResource],
        env: HashMap[String, String]): ContainerLaunchContext = {
    logInfo("Setting up container launch context")
    val amContainer = Records.newRecord(classOf[ContainerLaunchContext])
    amContainer.setLocalResources(localResources)
    amContainer.setEnvironment(env)

    val amMemory = calculateAMMemory(newApp)

    val javaOpts = ListBuffer[String]()

    // Add Xmx for AM memory
    javaOpts += "-Xmx" + amMemory + "m"

    val tmpDir = new Path(Environment.PWD.$(), YarnConfiguration.DEFAULT_CONTAINER_TEMP_DIR)
    javaOpts += "-Djava.io.tmpdir=" + tmpDir

    // TODO: Remove once cpuset version is pushed out.
    // The context is, default gc for server class machines ends up using all cores to do gc -
    // hence if there are multiple containers in same node, Spark GC affects all other containers'
    // performance (which can be that of other Spark containers)
    // Instead of using this, rely on cpusets by YARN to enforce "proper" Spark behavior in
    // multi-tenant environments. Not sure how default Java GC behaves if it is limited to subset
    // of cores on a node.
    val useConcurrentAndIncrementalGC = env.isDefinedAt("SPARK_USE_CONC_INCR_GC") &&
      java.lang.Boolean.parseBoolean(env("SPARK_USE_CONC_INCR_GC"))
    if (useConcurrentAndIncrementalGC) {
      // In our expts, using (default) throughput collector has severe perf ramifications in
      // multi-tenant machines
      javaOpts += "-XX:+UseConcMarkSweepGC"
      javaOpts += "-XX:+CMSIncrementalMode"
      javaOpts += "-XX:+CMSIncrementalPacing"
      javaOpts += "-XX:CMSIncrementalDutyCycleMin=0"
      javaOpts += "-XX:CMSIncrementalDutyCycle=10"
    }

    // SPARK_JAVA_OPTS is deprecated, but for backwards compatibility:
    sys.env.get("SPARK_JAVA_OPTS").foreach { opts =>
      sparkConf.set("spark.executor.extraJavaOptions", opts)
      sparkConf.set("spark.driver.extraJavaOptions", opts)
    }

    // TODO: it might be nicer to pass these as an internal environment variable rather than
    // as Java options, due to complications with string parsing of nested quotes.
    if (args.amClass == classOf[ExecutorLauncher].getName) {
      // If we are being launched in client mode, forward the spark-conf options
      // onto the executor launcher
      for ((k, v) <- sparkConf.getAll) {
        javaOpts += "-D" + k + "=" + "\\\"" + v + "\\\""
      }
    } else {
      // If we are being launched in standalone mode, capture and forward any spark
      // system properties (e.g. set by spark-class).
      for ((k, v) <- sys.props.filterKeys(_.startsWith("spark"))) {
        javaOpts += "-D" + k + "=" + "\\\"" + v + "\\\""
      }
      sys.props.get("spark.driver.extraJavaOptions").foreach(opts => javaOpts += opts)
      sys.props.get("spark.driver.libraryPath").foreach(p => javaOpts += s"-Djava.library.path=$p")
    }
    javaOpts += ClientBase.getLog4jConfiguration(localResources)

    // Command for the ApplicationMaster
    val commands = Seq(Environment.JAVA_HOME.$() + "/bin/java", "-server") ++
      javaOpts ++
      Seq(args.amClass, "--class", args.userClass, "--jar ", args.userJar,
        userArgsToString(args),
        "--executor-memory", args.executorMemory.toString,
        "--executor-cores", args.executorCores.toString,
        "--num-executors ", args.numExecutors.toString,
        "1>", ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout",
        "2>", ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr")

    logInfo("Command for starting the Spark ApplicationMaster: " + commands)

    // TODO: it would be nicer to just make sure there are no null commands here
    val printableCommands = commands.map(s => if (s == null) "null" else s).toList
    amContainer.setCommands(printableCommands)

    setupSecurityToken(amContainer)
    amContainer
  }
}

object ClientBase extends Logging {
  val SPARK_JAR: String = "__spark__.jar"
  val APP_JAR: String = "__app__.jar"
  val LOG4J_PROP: String = "log4j.properties"
  val LOG4J_CONF_ENV_KEY: String = "SPARK_LOG4J_CONF"
  val LOCAL_SCHEME = "local"
  val CONF_SPARK_YARN_SECONDARY_JARS = "spark.yarn.secondary.jars"

  def getSparkJar = sys.env.get("SPARK_JAR").getOrElse(SparkContext.jarOfClass(this.getClass).head)

  def populateHadoopClasspath(conf: Configuration, env: HashMap[String, String]) = {
    val classPathElementsToAdd = getYarnAppClasspath(conf) ++ getMRAppClasspath(conf)
    for (c <- classPathElementsToAdd.flatten) {
      YarnSparkHadoopUtil.addToEnvironment(
        env,
        Environment.CLASSPATH.name,
        c.trim,
        File.pathSeparator)
    }
    classPathElementsToAdd
  }

  private def getYarnAppClasspath(conf: Configuration): Option[Seq[String]] =
    Option(conf.getStrings(YarnConfiguration.YARN_APPLICATION_CLASSPATH)) match {
      case Some(s) => Some(s.toSeq)
      case None => getDefaultYarnApplicationClasspath
  }

  private def getMRAppClasspath(conf: Configuration): Option[Seq[String]] =
    Option(conf.getStrings("mapreduce.application.classpath")) match {
      case Some(s) => Some(s.toSeq)
      case None => getDefaultMRApplicationClasspath
    }

  def getDefaultYarnApplicationClasspath: Option[Seq[String]] = {
    val triedDefault = Try[Seq[String]] {
      val field = classOf[YarnConfiguration].getField("DEFAULT_YARN_APPLICATION_CLASSPATH")
      val value = field.get(null).asInstanceOf[Array[String]]
      value.toSeq
    } recoverWith {
      case e: NoSuchFieldException => Success(Seq.empty[String])
    }

    triedDefault match {
      case f: Failure[_] =>
        logError("Unable to obtain the default YARN Application classpath.", f.exception)
      case s: Success[_] =>
        logDebug(s"Using the default YARN application classpath: ${s.get.mkString(",")}")
    }

    triedDefault.toOption
  }

  /**
   * In Hadoop 0.23, the MR application classpath comes with the YARN application
   * classpath.  In Hadoop 2.0, it's an array of Strings, and in 2.2+ it's a String.
   * So we need to use reflection to retrieve it.
   */
  def getDefaultMRApplicationClasspath: Option[Seq[String]] = {
    val triedDefault = Try[Seq[String]] {
      val field = classOf[MRJobConfig].getField("DEFAULT_MAPREDUCE_APPLICATION_CLASSPATH")
      val value = if (field.getType == classOf[String]) {
        StringUtils.getStrings(field.get(null).asInstanceOf[String]).toArray
      } else {
        field.get(null).asInstanceOf[Array[String]]
      }
      value.toSeq
    } recoverWith {
      case e: NoSuchFieldException => Success(Seq.empty[String])
    }

    triedDefault match {
      case f: Failure[_] =>
        logError("Unable to obtain the default MR Application classpath.", f.exception)
      case s: Success[_] =>
        logDebug(s"Using the default MR application classpath: ${s.get.mkString(",")}")
    }

    triedDefault.toOption
  }


  /**
   * Returns the java command line argument for setting up log4j. If there is a log4j.properties
   * in the given local resources, it is used, otherwise the SPARK_LOG4J_CONF environment variable
   * is checked.
   */
  def getLog4jConfiguration(localResources: HashMap[String, LocalResource]): String = {
    var log4jConf = LOG4J_PROP
    if (!localResources.contains(log4jConf)) {
      log4jConf = System.getenv(LOG4J_CONF_ENV_KEY) match {
        case conf: String =>
          val confUri = new URI(conf)
          if (ClientBase.LOCAL_SCHEME.equals(confUri.getScheme())) {
            "file://" + confUri.getPath()
          } else {
            ClientBase.LOG4J_PROP
          }
        case null => "log4j-spark-container.properties"
      }
    }
    " -Dlog4j.configuration=" + log4jConf
  }

  def populateClasspath(conf: Configuration, sparkConf: SparkConf, log4jConf: String,
      env: HashMap[String, String], extraClassPath: Option[String] = None) {

    if (log4jConf != null) {
      // If a custom log4j config file is provided as a local: URI, add its parent directory to the
      // classpath. Note that this only works if the custom config's file name is
      // "log4j.properties".
      val localPath = getLocalPath(log4jConf)
      if (localPath != null) {
        val parentPath = new File(localPath).getParent()
        YarnSparkHadoopUtil.addToEnvironment(env, Environment.CLASSPATH.name, parentPath,
          File.pathSeparator)
      }
    }

    /** Add entry to the classpath. */
    def addClasspathEntry(path: String) = YarnSparkHadoopUtil.addToEnvironment(env,
      Environment.CLASSPATH.name, path, File.pathSeparator)
    /** Add entry to the classpath. Interpreted as a path relative to the working directory. */
    def addPwdClasspathEntry(entry: String) =
      addClasspathEntry(Environment.PWD.$() + Path.SEPARATOR + entry)

    extraClassPath.foreach(addClasspathEntry)

    val cachedSecondaryJarLinks =
      sparkConf.getOption(CONF_SPARK_YARN_SECONDARY_JARS).getOrElse("").split(",")
        .filter(_.nonEmpty)
    // Normally the users app.jar is last in case conflicts with spark jars
    if (sparkConf.get("spark.yarn.user.classpath.first", "false").toBoolean) {
      addPwdClasspathEntry(APP_JAR)
      cachedSecondaryJarLinks.foreach(addPwdClasspathEntry)
      addPwdClasspathEntry(SPARK_JAR)
      ClientBase.populateHadoopClasspath(conf, env)
    } else {
      addPwdClasspathEntry(SPARK_JAR)
      ClientBase.populateHadoopClasspath(conf, env)
      addPwdClasspathEntry(APP_JAR)
      cachedSecondaryJarLinks.foreach(addPwdClasspathEntry)
    }
    // Append all class files and jar files under the working directory to the classpath.
    addClasspathEntry(Environment.PWD.$())
    addPwdClasspathEntry("*")
  }

  /**
   * Returns the local path if the URI is a "local:" URI, or null otherwise.
   */
  private def getLocalPath(resource: String): String = {
    val uri = new URI(resource)
    if (LOCAL_SCHEME.equals(uri.getScheme())) {
      return uri.getPath()
    }
    null
  }

}
