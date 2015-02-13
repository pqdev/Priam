#!/bin/bash

# set up some variables
TOMCAT_HOME=/var/lib/tomcat7
CASSANDRA_HOME=/usr/share/cassandra
CASSANDRA_CONF=/etc/cassandra

### deploy priam
cp priam-web/build/libs/priam-web-2.0.10.war $TOMCAT_HOME/webapps/Priam.war
cp priam-cass-extensions/build/libs/priam-cass-extensions-2.0.10.jar $CASSANDRA_HOME/lib

### add to cassandra
sed -i '$aJAVA_AGENT="$JAVA_AGENT -javaagent:$CASSANDRA_HOME/lib/priam-cass-extensions-2.0.10.jar"' $CASSANDRA_HOME/cassandra.in.sh

### fix cassandra/jmx
sudo sed -i '$apublic_ip=$(ec2metadata --public-ipv4)' $CASSANDRA_CONF/cassandra-env.sh
sudo sed -i '$aJVM_OPTS="$JVM_OPTS -Djava.rmi.server.hostname=$public_ip"' $CASSANDRA_CONF/cassandra-env.sh

# add helper pages to webapp
cp priam.html $TOMCAT_HOME/webapps/ROOT
cd $TOMCAT_HOME/webapps/ROOT
sed -i '1a allowLinking="true" ' META-INF/context.xml
ln -s /var/log/tomcat7/priam.log
ln -s /var/log/tomcat7/catalina.out
ln -s /var/log/cassandra/system.log
ln -s /var/log/cloud-init-output.log
cd -
