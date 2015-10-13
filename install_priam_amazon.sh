#!/bin/bash

# set up some variables
TOMCAT_HOME=/var/lib/tomcat7
CASSANDRA_HOME=/usr/share/cassandra
# amazon uses different dir than ubuntu
CASSANDRA_CONF=/etc/cassandra/conf
CATALINA_CONF=/usr/share/tomcat7/conf

### deploy priam
cp priam-web/build/libs/priam-web-2.0.10.war $TOMCAT_HOME/webapps/Priam.war
cp priam-cass-extensions/build/libs/priam-cass-extensions-2.0.10.jar $CASSANDRA_HOME/lib

### add to cassandra
sed -i '$aJAVA_AGENT="$JAVA_AGENT -javaagent:$CASSANDRA_HOME/lib/priam-cass-extensions-2.0.10.jar"' $CASSANDRA_HOME/cassandra.in.sh

### fix cassandra/jmx
sudo sed -i '$apublic_ip=$(ec2-metadata --public-ipv4 | awk "{print $2}")' $CASSANDRA_CONF/cassandra-env.sh
sudo sed -i '$aJVM_OPTS="$JVM_OPTS -Djava.rmi.server.hostname=$public_ip"' $CASSANDRA_CONF/cassandra-env.sh

# allow links in tomcat
sed -i 's/<Context>/<Context allowLinking="true">/' $CATALINA_CONF/context.xml

# add helper pages to webapp
mkdir -m 777 $TOMCAT_HOME/webapps/ROOT/
cp priam.html $TOMCAT_HOME/webapps/ROOT/
cd $TOMCAT_HOME/webapps/ROOT/
chmod 755 /var/log/tomcat7/
chmod 644 /var/log/tomcat7/*
ln -s /var/log/tomcat7/priam.log
ln -s /var/log/tomcat7/catalina.out
ln -s /var/log/cassandra/system.log
ln -s /var/log/cassandra/cassandra.log
ln -s /var/log/datastax-agent/agent.log
ln -s /var/log/datastax-agent/startup.log
ln -s /var/log/cloud-init-output.log
cd -
