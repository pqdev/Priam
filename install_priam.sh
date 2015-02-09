#!/bin/bash

# set up some variables
TOMCAT_HOME=/var/lib/tomcat7
CASSANDRA_CONF=/etc/cassandra
CASSANDRA_HOME=/usr/share/cassandra
public_ip=$(ec2metadata --public-ipv4)

# fix hosts
sed -i "s/127.0.0.1 localhost/127.0.0.1 $HOSTNAME localhost/" /etc/hosts

# fix cassandra/jmx
sudo sed -i "\$aJVM_OPTS=\"\$JVM_OPTS -Djava.rmi.server.hostname=$public_ip\"" $CASSANDRA_CONF/cassandra-env.sh

### deploy priam 
cp priam-web/build/libs/priam-web-2.0.10.war $TOMCAT_HOME/webapps/Priam.war 
cp priam-cass-extensions/build/libs/priam-cass-extensions-2.0.10.jar $CASSANDRA_HOME/lib 

sed -i '$aJAVA_AGENT="$JAVA_AGENT -javaagent:$CASSANDRA_HOME/lib/priam-cass-extensions-2.0.10.jar"' $CASSANDRA_HOME/cassandra.in.sh 

# add helper pages to webapp
cp priam.html $TOMCAT_HOME/webapps/ROOT
sed -i '1a allowLinking="true" ' $TOMCAT_HOME/webapps/ROOT/META-INF/context.xml
cd $TOMCAT_HOME/webapps/ROOT 
ln -s $TOMCAT_HOME/logs/priam.log 
ln -s $TOMCAT_HOME/logs/catalina.out 
ln -s /var/log/cassandra/system.log 
ln -s /var/log/cloud-init-output.log
cd - 
