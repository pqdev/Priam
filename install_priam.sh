#!/bin/bash
TOMCAT_HOME=/var/lib/tomcat7
CASSANDRA_CONF=/etc/cassandra
CASSANDRA_HOME=/usr/share/cassandra

### deploy priam 
cp priam-web/build/libs/priam-web-2.0.10.war $TOMCAT_HOME/webapps/Priam.war 
cp priam-cass-extensions/build/libs/priam-cass-extensions-2.0.10.jar $CASSANDRA_HOME/lib 
sed -i '$aJAVA_AGENT="$JAVA_AGENT -javaagent:$CASSANDRA_HOME/lib/priam-cass-extensions-2.0.10.jar"' $CASSANDRA_HOME/cassandra.in.sh 

aws --region eu-west-1 s3 cp s3://xrs-cassandra/priam.html $TOMCAT_HOME/webapps/ROOT
sed -i '1a allowLinking="true" ' $TOMCAT_HOME/webapps/ROOT/META-INF/context.xml
cd $TOMCAT_HOME/webapps/ROOT 
ln -s $TOMCAT_HOME/logs/priam.log 
ln -s $TOMCAT_HOME/logs/catalina.out 
ln -s /var/log/cassandra/system.log 
cd - 
