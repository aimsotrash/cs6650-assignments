# /opt/tomcat9/bin/setenv.sh on every ingest server (catalina.sh reads it at startup; restart tomcat after a change)
# fill in the broker's private IP and the skitrak password
export RABBIT_HOST=BROKER_PRIVATE_IP
export RABBIT_USER=skitrak
export RABBIT_PASS=CHANGE_ME
# channels in the pool (one per concurrent publish; tomcat runs up to 200 request threads)
export CHANNEL_POOL_SIZE=64
