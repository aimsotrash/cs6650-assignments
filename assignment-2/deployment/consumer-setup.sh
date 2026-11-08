#!/bin/bash
# Sets up the consumer host (Amazon Linux 2023): Java 11 like lab 1, the jar, and the systemd service.
# copy consumer.jar, skitrak-consumer.service and a filled-in consumer.env to the home directory first, then:
#   sudo bash consumer-setup.sh
set -euo pipefail
cd "$(dirname "$0")"

dnf install -y java-11-amazon-corretto-headless
mkdir -p /opt/skitrak-consumer
cp consumer.jar /opt/skitrak-consumer/consumer.jar
cp consumer.env /etc/skitrak-consumer.env
chmod 600 /etc/skitrak-consumer.env
chown ec2-user /etc/skitrak-consumer.env
cp skitrak-consumer.service /etc/systemd/system/skitrak-consumer.service

systemctl daemon-reload
systemctl enable skitrak-consumer
systemctl restart skitrak-consumer
sleep 5
systemctl --no-pager status skitrak-consumer | head -5
curl -s localhost:8081/health; echo
