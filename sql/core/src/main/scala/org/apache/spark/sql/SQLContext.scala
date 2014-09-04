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

package org.apache.spark.sql

import scala.language.implicitConversions
import scala.reflect.runtime.universe.TypeTag

import org.apache.hadoop.conf.Configuration

import org.apache.spark.annotation.{AlphaComponent, DeveloperApi, Experimental}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.dsl.ExpressionConversions
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.optimizer.Optimizer
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.columnar.InMemoryRelation
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.SparkStrategies
import org.apache.spark.sql.json._
import org.apache.spark.sql.parquet.ParquetRelation
import org.apache.spark.SparkContext

/**
 * :: AlphaComponent ::
 * The entry point for running relational queries using Spark.  Allows the creation of [[SchemaRDD]]
 * objects and the execution of SQL queries.
 *
 * @groupname userf Spark SQL Functions
 * @groupname Ungrouped Support functions for language integrated queries.
 */
@AlphaComponent
class SQLContext(@transient val sparkContext: SparkContext)
  extends Logging
  with SQLConf
  with ExpressionConversions
  with Serializable {

  self =>

  @transient
  protected[sql] lazy val catalog: Catalog = new SimpleCatalog(true)
  @transient
  protected[sql] lazy val analyzer: Analyzer =
    new Analyzer(catalog, EmptyFunctionRegistry, caseSensitive = true)
  @transient
  protected[sql] val optimizer = Optimizer
  @transient
  protected[sql] val parser = new catalyst.SqlParser

  protected[sql] def parseSql(sql: String): LogicalPlan = parser(sql)
  protected[sql] def executeSql(sql: String): this.QueryExecution = executePlan(parseSql(sql))
  protected[sql] def executePlan(plan: LogicalPlan): this.QueryExecution =
    new this.QueryExecution { val logical = plan }

  /**
   * :: DeveloperApi ::
   * Allows catalyst LogicalPlans to be executed as a SchemaRDD.  Note that the LogicalPlan
   * interface is considered internal, and thus not guaranteed to be stable.  As a result, using
   * them directly is not recommended.
   */
  @DeveloperApi
  implicit def logicalPlanToSparkQuery(plan: LogicalPlan): SchemaRDD = new SchemaRDD(this, plan)

  /**
   * Creates a SchemaRDD from an RDD of case classes.
   *
   * @group userf
   */
  implicit def createSchemaRDD[A <: Product: TypeTag](rdd: RDD[A]) =
    new SchemaRDD(this, SparkLogicalPlan(ExistingRdd.fromProductRdd(rdd))(self))

  /**
   * :: DeveloperApi ::
   * Creates a [[SchemaRDD]] from an [[RDD]] containing [[Row]]s by applying a schema to this RDD.
   * It is important to make sure that the structure of every [[Row]] of the provided RDD matches
   * the provided schema. Otherwise, there will be runtime exception.
   * Example:
   * {{{
   *  import org.apache.spark.sql._
   *  val sqlContext = new org.apache.spark.sql.SQLContext(sc)
   *
   *  val schema =
   *    StructType(
   *      StructField("name", StringType, false) ::
   *      StructField("age", IntegerType, true) :: Nil)
   *
   *  val people =
   *    sc.textFile("examples/src/main/resources/people.txt").map(
   *      _.split(",")).map(p => Row(p(0), p(1).trim.toInt))
   *  val peopleSchemaRDD = sqlContext. applySchema(people, schema)
   *  peopleSchemaRDD.printSchema
   *  // root
   *  // |-- name: string (nullable = false)
   *  // |-- age: integer (nullable = true)
   *
   *    peopleSchemaRDD.registerAsTable("people")
   *  sqlContext.sql("select name from people").collect.foreach(println)
   * }}}
   *
   * @group userf
   */
  @DeveloperApi
  def applySchema(rowRDD: RDD[Row], schema: StructType): SchemaRDD = {
    // TODO: use MutableProjection when rowRDD is another SchemaRDD and the applied
    // schema differs from the existing schema on any field data type.
    val logicalPlan = SparkLogicalPlan(ExistingRdd(schema.toAttributes, rowRDD))(self)
    new SchemaRDD(this, logicalPlan)
  }

  /**
   * Loads a Parquet file, returning the result as a [[SchemaRDD]].
   *
   * @group userf
   */
  def parquetFile(path: String): SchemaRDD =
    new SchemaRDD(this, parquet.ParquetRelation(path, Some(sparkContext.hadoopConfiguration), this))

  /**
   * Loads a JSON file (one object per line), returning the result as a [[SchemaRDD]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonFile(path: String): SchemaRDD = jsonFile(path, 1.0)

  /**
   * :: Experimental ::
   * Loads a JSON file (one object per line) and applies the given schema,
   * returning the result as a [[SchemaRDD]].
   *
   * @group userf
   */
  @Experimental
  def jsonFile(path: String, schema: StructType): SchemaRDD = {
    val json = sparkContext.textFile(path)
    jsonRDD(json, schema)
  }

  /**
   * :: Experimental ::
   */
  @Experimental
  def jsonFile(path: String, samplingRatio: Double): SchemaRDD = {
    val json = sparkContext.textFile(path)
    jsonRDD(json, samplingRatio)
  }

  /**
   * Loads an RDD[String] storing JSON objects (one object per record), returning the result as a
   * [[SchemaRDD]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonRDD(json: RDD[String]): SchemaRDD = jsonRDD(json, 1.0)

  /**
   * :: Experimental ::
   * Loads an RDD[String] storing JSON objects (one object per record) and applies the given schema,
   * returning the result as a [[SchemaRDD]].
   *
   * @group userf
   */
  @Experimental
  def jsonRDD(json: RDD[String], schema: StructType): SchemaRDD = {
    val appliedSchema =
      Option(schema).getOrElse(JsonRDD.nullTypeToStringType(JsonRDD.inferSchema(json, 1.0)))
    val rowRDD = JsonRDD.jsonStringToRow(json, appliedSchema)
    applySchema(rowRDD, appliedSchema)
  }

  /**
   * :: Experimental ::
   */
  @Experimental
  def jsonRDD(json: RDD[String], samplingRatio: Double): SchemaRDD = {
    val appliedSchema = JsonRDD.nullTypeToStringType(JsonRDD.inferSchema(json, samplingRatio))
    val rowRDD = JsonRDD.jsonStringToRow(json, appliedSchema)
    applySchema(rowRDD, appliedSchema)
  }

  /**
   * :: Experimental ::
   * Creates an empty parquet file with the schema of class `A`, which can be registered as a table.
   * This registered table can be used as the target of future `insertInto` operations.
   *
   * {{{
   *   val sqlContext = new SQLContext(...)
   *   import sqlContext._
   *
   *   case class Person(name: String, age: Int)
   *   createParquetFile[Person]("path/to/file.parquet").registerAsTable("people")
   *   sql("INSERT INTO people SELECT 'michael', 29")
   * }}}
   *
   * @tparam A A case class type that describes the desired schema of the parquet file to be
   *           created.
   * @param path The path where the directory containing parquet metadata should be created.
   *             Data inserted into this table will also be stored at this location.
   * @param allowExisting When false, an exception will be thrown if this directory already exists.
   * @param conf A Hadoop configuration object that can be used to specify options to the parquet
   *             output format.
   *
   * @group userf
   */
  @Experimental
  def createParquetFile[A <: Product : TypeTag](
      path: String,
      allowExisting: Boolean = true,
      conf: Configuration = new Configuration()): SchemaRDD = {
    new SchemaRDD(
      this,
      ParquetRelation.createEmpty(
        path, ScalaReflection.attributesFor[A], allowExisting, conf, this))
  }

  /**
   * Registers the given RDD as a temporary table in the catalog.  Temporary tables exist only
   * during the lifetime of this instance of SQLContext.
   *
   * @group userf
   */
  def registerRDDAsTable(rdd: SchemaRDD, tableName: String): Unit = {
    catalog.registerTable(None, tableName, rdd.logicalPlan)
  }

  /**
   * Executes a SQL query using Spark, returning the result as a SchemaRDD.
   *
   * @group userf
   */
  def sql(sqlText: String): SchemaRDD = new SchemaRDD(this, parseSql(sqlText))

  /** Returns the specified table as a SchemaRDD */
  def table(tableName: String): SchemaRDD =
    new SchemaRDD(this, catalog.lookupRelation(None, tableName))

  /** Caches the specified table in-memory. */
  def cacheTable(tableName: String): Unit = {
    val currentTable = table(tableName).queryExecution.analyzed
    val asInMemoryRelation = currentTable match {
      case _: InMemoryRelation =>
        currentTable.logicalPlan

      case _ =>
        InMemoryRelation(useCompression, executePlan(currentTable).executedPlan)
    }

    catalog.registerTable(None, tableName, asInMemoryRelation)
  }

  /** Removes the specified table from the in-memory cache. */
  def uncacheTable(tableName: String): Unit = {
    table(tableName).queryExecution.analyzed match {
      // This is kind of a hack to make sure that if this was just an RDD registered as a table,
      // we reregister the RDD as a table.
      case inMem @ InMemoryRelation(_, _, e: ExistingRdd) =>
        inMem.cachedColumnBuffers.unpersist()
        catalog.unregisterTable(None, tableName)
        catalog.registerTable(None, tableName, SparkLogicalPlan(e)(self))
      case inMem: InMemoryRelation =>
        inMem.cachedColumnBuffers.unpersist()
        catalog.unregisterTable(None, tableName)
      case plan => throw new IllegalArgumentException(s"Table $tableName is not cached: $plan")
    }
  }

  /** Returns true if the table is currently cached in-memory. */
  def isCached(tableName: String): Boolean = {
    val relation = table(tableName).queryExecution.analyzed
    relation match {
      case _: InMemoryRelation => true
      case _ => false
    }
  }

  protected[sql] class SparkPlanner extends SparkStrategies {
    val sparkContext: SparkContext = self.sparkContext

    val sqlContext: SQLContext = self

    def codegenEnabled = self.codegenEnabled

    def numPartitions = self.numShufflePartitions

    val strategies: Seq[Strategy] =
      CommandStrategy(self) ::
      TakeOrdered ::
      HashAggregation ::
      LeftSemiJoin ::
      HashJoin ::
      InMemoryScans ::
      ParquetOperations ::
      BasicOperators ::
      CartesianProduct ::
      BroadcastNestedLoopJoin :: Nil

    /**
     * Used to build table scan operators where complex projection and filtering are done using
     * separate physical operators.  This function returns the given scan operator with Project and
     * Filter nodes added only when needed.  For example, a Project operator is only used when the
     * final desired output requires complex expressions to be evaluated or when columns can be
     * further eliminated out after filtering has been done.
     *
     * The `prunePushedDownFilters` parameter is used to remove those filters that can be optimized
     * away by the filter pushdown optimization.
     *
     * The required attributes for both filtering and expression evaluation are passed to the
     * provided `scanBuilder` function so that it can avoid unnecessary column materialization.
     */
    def pruneFilterProject(
        projectList: Seq[NamedExpression],
        filterPredicates: Seq[Expression],
        prunePushedDownFilters: Seq[Expression] => Seq[Expression],
        scanBuilder: Seq[Attribute] => SparkPlan): SparkPlan = {

      val projectSet = projectList.flatMap(_.references).toSet
      val filterSet = filterPredicates.flatMap(_.references).toSet
      val filterCondition = prunePushedDownFilters(filterPredicates).reduceLeftOption(And)

      // Right now we still use a projection even if the only evaluation is applying an alias
      // to a column.  Since this is a no-op, it could be avoided. However, using this
      // optimization with the current implementation would change the output schema.
      // TODO: Decouple final output schema from expression evaluation so this copy can be
      // avoided safely.

      if (projectList.toSet == projectSet && filterSet.subsetOf(projectSet)) {
        // When it is possible to just use column pruning to get the right projection and
        // when the columns of this projection are enough to evaluate all filter conditions,
        // just do a scan followed by a filter, with no extra project.
        val scan = scanBuilder(projectList.asInstanceOf[Seq[Attribute]])
        filterCondition.map(Filter(_, scan)).getOrElse(scan)
      } else {
        val scan = scanBuilder((projectSet ++ filterSet).toSeq)
        Project(projectList, filterCondition.map(Filter(_, scan)).getOrElse(scan))
      }
    }
  }

  @transient
  protected[sql] val planner = new SparkPlanner

  @transient
  protected[sql] lazy val emptyResult = sparkContext.parallelize(Seq.empty[Row], 1)

  /**
   * Prepares a planned SparkPlan for execution by inserting shuffle operations as needed.
   */
  @transient
  protected[sql] val prepareForExecution = new RuleExecutor[SparkPlan] {
    val batches =
      Batch("Add exchange", Once, AddExchange(self)) :: Nil
  }

  /**
   * :: DeveloperApi ::
   * The primary workflow for executing relational queries using Spark.  Designed to allow easy
   * access to the intermediate phases of query execution for developers.
   */
  @DeveloperApi
  protected abstract class QueryExecution {
    def logical: LogicalPlan

    lazy val analyzed = analyzer(logical)
    lazy val optimizedPlan = optimizer(analyzed)
    // TODO: Don't just pick the first one...
    lazy val sparkPlan = {
      SparkPlan.currentContext.set(self)
      planner(optimizedPlan).next()
    }
    // executedPlan should not be used to initialize any SparkPlan. It should be
    // only used for execution.
    lazy val executedPlan: SparkPlan = prepareForExecution(sparkPlan)

    /** Internal version of the RDD. Avoids copies and has no schema */
    lazy val toRdd: RDD[Row] = executedPlan.execute()

    protected def stringOrError[A](f: => A): String =
      try f.toString catch { case e: Throwable => e.toString }

    def simpleString: String = stringOrError(executedPlan)

    override def toString: String =
      s"""== Logical Plan ==
         |${stringOrError(analyzed)}
         |== Optimized Logical Plan ==
         |${stringOrError(optimizedPlan)}
         |== Physical Plan ==
         |${stringOrError(executedPlan)}
         |Code Generation: ${executedPlan.codegenEnabled}
         |== RDD ==
         |${stringOrError(toRdd.toDebugString)}
      """.stripMargin.trim
  }

  /**
   * Peek at the first row of the RDD and infer its schema.
   * It is only used by PySpark.
   */
  private[sql] def inferSchema(rdd: RDD[Map[String, _]]): SchemaRDD = {
    import scala.collection.JavaConversions._

    def typeOfComplexValue: PartialFunction[Any, DataType] = {
      case c: java.util.Calendar => TimestampType
      case c: java.util.List[_] =>
        ArrayType(typeOfObject(c.head))
      case c: java.util.Map[_, _] =>
        val (key, value) = c.head
        MapType(typeOfObject(key), typeOfObject(value))
      case c if c.getClass.isArray =>
        val elem = c.asInstanceOf[Array[_]].head
        ArrayType(typeOfObject(elem))
      case c => throw new Exception(s"Object of type $c cannot be used")
    }
    def typeOfObject = ScalaReflection.typeOfObject orElse typeOfComplexValue

    val firstRow = rdd.first()
    val fields = firstRow.map {
      case (fieldName, obj) => StructField(fieldName, typeOfObject(obj), true)
    }.toSeq

    applySchemaToPythonRDD(rdd, StructType(fields))
  }

  /**
   * Parses the data type in our internal string representation. The data type string should
   * have the same format as the one generated by `toString` in scala.
   * It is only used by PySpark.
   */
  private[sql] def parseDataType(dataTypeString: String): DataType = {
    val parser = org.apache.spark.sql.catalyst.types.DataType
    parser(dataTypeString)
  }

  /**
   * Apply a schema defined by the schemaString to an RDD. It is only used by PySpark.
   */
  private[sql] def applySchemaToPythonRDD(
      rdd: RDD[Map[String, _]],
      schemaString: String): SchemaRDD = {
    val schema = parseDataType(schemaString).asInstanceOf[StructType]
    applySchemaToPythonRDD(rdd, schema)
  }

  /**
   * Apply a schema defined by the schema to an RDD. It is only used by PySpark.
   */
  private[sql] def applySchemaToPythonRDD(
      rdd: RDD[Map[String, _]],
      schema: StructType): SchemaRDD = {
    // TODO: We should have a better implementation once we do not turn a Python side record
    // to a Map.
    import scala.collection.JavaConversions._
    import scala.collection.convert.Wrappers.{JListWrapper, JMapWrapper}

    def needsConversion(dataType: DataType): Boolean = dataType match {
      case ByteType => true
      case ShortType => true
      case FloatType => true
      case TimestampType => true
      case ArrayType(_, _) => true
      case MapType(_, _, _) => true
      case StructType(_) => true
      case other => false
    }

    // Converts value to the type specified by the data type.
    // Because Python does not have data types for TimestampType, FloatType, ShortType, and
    // ByteType, we need to explicitly convert values in columns of these data types to the desired
    // JVM data types.
    def convert(obj: Any, dataType: DataType): Any = (obj, dataType) match {
      // TODO: We should check nullable
      case (null, _) => null

      case (c: java.util.List[_], ArrayType(elementType, _)) =>
        val converted = c.map { e => convert(e, elementType)}
        JListWrapper(converted)

      case (c: java.util.Map[_, _], struct: StructType) =>
        val row = new GenericMutableRow(struct.fields.length)
        struct.fields.zipWithIndex.foreach {
          case (field, i) =>
            val value = convert(c.get(field.name), field.dataType)
            row.update(i, value)
        }
        row

      case (c: java.util.Map[_, _], MapType(keyType, valueType, _)) =>
        val converted = c.map {
          case (key, value) =>
            (convert(key, keyType), convert(value, valueType))
        }
        JMapWrapper(converted)

      case (c, ArrayType(elementType, _)) if c.getClass.isArray =>
        val converted = c.asInstanceOf[Array[_]].map(e => convert(e, elementType))
        converted: Seq[Any]

      case (c: java.util.Calendar, TimestampType) => new java.sql.Timestamp(c.getTime().getTime())
      case (c: Int, ByteType) => c.toByte
      case (c: Int, ShortType) => c.toShort
      case (c: Double, FloatType) => c.toFloat

      case (c, _) => c
    }

    val convertedRdd = if (schema.fields.exists(f => needsConversion(f.dataType))) {
      rdd.map(m => m.map { case (key, value) => (key, convert(value, schema(key).dataType)) })
    } else {
      rdd
    }

    val rowRdd = convertedRdd.mapPartitions { iter =>
      val row = new GenericMutableRow(schema.fields.length)
      val fieldsWithIndex = schema.fields.zipWithIndex
      iter.map { m =>
        // We cannot use m.values because the order of values returned by m.values may not
        // match fields order.
        fieldsWithIndex.foreach {
          case (field, i) =>
            val value =
              m.get(field.name).flatMap(v => Option(v)).map(v => convert(v, field.dataType)).orNull
            row.update(i, value)
        }

        row: Row
      }
    }

    new SchemaRDD(this, SparkLogicalPlan(ExistingRdd(schema.toAttributes, rowRdd))(self))
  }
}
