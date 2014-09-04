#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Shell script for starting the Spark SQL Thrift server

# Enter posix mode for bash
set -o posix

# Figure out where Spark is installed
FWDIR="$(cd `dirname $0`/..; pwd)"

CLASS="org.apache.spark.sql.hive.thriftserver.HiveThriftServer2"

function usage {
  echo "Usage: ./sbin/start-thriftserver [options] [thrift server options]"
  pattern="usage"
  pattern+="\|Spark assembly has been built with Hive"
  pattern+="\|NOTE: SPARK_PREPEND_CLASSES is set"
  pattern+="\|Spark Command: "
  pattern+="\|======="
  pattern+="\|--help"

  $FWDIR/bin/spark-submit --help 2>&1 | grep -v Usage 1>&2
  echo
  echo "Thrift server options:"
  $FWDIR/bin/spark-class $CLASS --help 2>&1 | grep -v "$pattern" 1>&2
}

function ensure_arg_number {
  arg_number=$1
  at_least=$2

  if [[ $arg_number -lt $at_least ]]; then
    usage
    exit 1
  fi
}

if [[ "$@" = --help ]] || [[ "$@" = -h ]]; then
  usage
  exit 0
fi

THRIFT_SERVER_ARGS=()
SUBMISSION_ARGS=()

while (($#)); do
  case $1 in
    --hiveconf)
      ensure_arg_number $# 2
      THRIFT_SERVER_ARGS+=($1); shift
      THRIFT_SERVER_ARGS+=($1); shift
      ;;

    *)
      SUBMISSION_ARGS+=($1); shift
      ;;
  esac
done

eval exec "$FWDIR"/bin/spark-submit --class $CLASS ${SUBMISSION_ARGS[*]} spark-internal ${THRIFT_SERVER_ARGS[*]}
