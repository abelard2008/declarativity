#!/bin/sh
# Start a BFS data node running on the localhost.

bin=`dirname "$0"`
bin=`cd "$bin"; pwd`

. "$bin"/hadoop-config.sh

if [ -f "${HADOOP_CONF_DIR}/hadoop-env.sh" ]; then
  . "${HADOOP_CONF_DIR}/hadoop-env.sh"
fi

# CLASSPATH initially contains $HADOOP_CONF_DIR
CLASSPATH="${HADOOP_CONF_DIR}"
CLASSPATH=${CLASSPATH}:$JAVA_HOME/lib/tools.jar

# for developers, add Hadoop classes to CLASSPATH
if [ -d "$HADOOP_HOME/build/classes" ]; then
  CLASSPATH=${CLASSPATH}:$HADOOP_HOME/build/classes
fi
if [ -d "$HADOOP_HOME/build/webapps" ]; then
  CLASSPATH=${CLASSPATH}:$HADOOP_HOME/build
fi
if [ -d "$HADOOP_HOME/build/test/classes" ]; then
  CLASSPATH=${CLASSPATH}:$HADOOP_HOME/build/test/classes
fi

# so that filenames w/ spaces are handled correctly in loops below
IFS=

# for releases, add core hadoop jar & webapps to CLASSPATH
if [ -d "$HADOOP_HOME/webapps" ]; then
  CLASSPATH=${CLASSPATH}:$HADOOP_HOME
fi
for f in $HADOOP_HOME/hadoop-*-core.jar; do
  CLASSPATH=${CLASSPATH}:$f;
done

# add libs to CLASSPATH
for f in $HADOOP_HOME/lib/*.jar; do
  CLASSPATH=${CLASSPATH}:$f;
done

for f in $HADOOP_HOME/lib/jetty-ext/*.jar; do
  CLASSPATH=${CLASSPATH}:$f;
done

for f in $HADOOP_HOME/hadoop-*-tools.jar; do
  TOOL_PATH=${TOOL_PATH}:$f;
done
for f in $HADOOP_HOME/build/hadoop-*-tools.jar; do
  TOOL_PATH=${TOOL_PATH}:$f;
done

# add user-specified CLASSPATH last
if [ "$HADOOP_CLASSPATH" != "" ]; then
  CLASSPATH=${CLASSPATH}:${HADOOP_CLASSPATH}
fi

# get log directory
if [ "$HADOOP_LOG_DIR" = "" ]; then
  export HADOOP_LOG_DIR="$HADOOP_HOME/logs"
  fi
  mkdir -p "$HADOOP_LOG_DIR"

if [ "$HADOOP_PID_DIR" = "" ]; then
  HADOOP_PID_DIR=/tmp
fi

command=bfsdatanode
log=$HADOOP_LOG_DIR/hadoop-$command-`hostname`.out
pid=$HADOOP_PID_DIR/hadoop-$command.pid

BFS_DATA_DIR=/tmp/bfs_data
# XXX: for now, we just remove the old data directory on startup
echo "Removing data directory $BFS_DATA_DIR"
rm -r $BFS_DATA_DIR

export MASTERFILE=$HADOOP_CONF_DIR/masters
export SLAVEFILE=$HADOOP_CONF_DIR/slaves
echo "Starting BFS data node, logging to $log"
nohup $JAVA -cp "$CLASSPATH" bfs.DataNode $BFS_DATA_DIR > "$log" 2>&1 < /dev/null &
echo $! > $pid

