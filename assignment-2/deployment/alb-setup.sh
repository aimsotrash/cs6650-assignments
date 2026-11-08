#!/bin/bash
# Creates the Application Load Balancer in front of the ingest servers (run from a machine with the AWS CLI).
#   CLIENT_IP=<load client's public IP> bash alb-setup.sh <server instance id> [<server instance id> ...]
# More servers later: aws elbv2 register-targets --target-group-arn <tg arn> --targets Id=<instance id>
set -euo pipefail
: "${CLIENT_IP:?set CLIENT_IP to the public IP of the load client instance}"
R=us-west-2
VPC=vpc-06da7ee15775ab84e
SUBNETS="subnet-0f5878244c619bdd8 subnet-007a04f70f82a9f10"   # us-west-2a and us-west-2b (an ALB needs two AZs)
APP_SG=sg-0db25d1d7256b1e35      # cs6650-lab1-sg, on the ingest servers
MY_IP=${MY_IP:-63.208.141.34/32}   # my laptop's IP, for testing from it (never 0.0.0.0/0, lab 1)

# the ALB takes HTTP from the load client and my IP; the servers take 8080 from the ALB.
# the ALB is internet-facing, so its name resolves to public IPs even inside the VPC and the client's requests
# arrive from the client's PUBLIC IP: a rule naming the client's security group doesn't match them.
# (that IP changes when the lab restarts the instance; add the new one with authorize-security-group-ingress)
ALB_SG=$(aws ec2 create-security-group --region $R --group-name cs6650-a2-alb-sg --description "CS6650 A2 ALB" \
  --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --region $R --group-id $ALB_SG --ip-permissions \
  "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=$CLIENT_IP/32}]" \
  "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=$MY_IP}]" > /dev/null
aws ec2 authorize-security-group-ingress --region $R --group-id $APP_SG --ip-permissions \
  "IpProtocol=tcp,FromPort=8080,ToPort=8080,UserIdGroupPairs=[{GroupId=$ALB_SG}]" > /dev/null

# target group: the servers on 8080; health check GET /skitrak/health every 30 s, timeout 5 s, healthy 2, unhealthy 3
TG=$(aws elbv2 create-target-group --region $R --name cs6650-a2-tg --protocol HTTP --port 8080 --vpc-id $VPC \
  --target-type instance --health-check-path /skitrak/health --health-check-interval-seconds 30 \
  --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 \
  --query 'TargetGroups[0].TargetGroupArn' --output text)
# sticky sessions (required for WebSocket): the ALB's own cookie (AWSALB), kept for a day
aws elbv2 modify-target-group-attributes --region $R --target-group-arn $TG --attributes \
  Key=stickiness.enabled,Value=true Key=stickiness.type,Value=lb_cookie \
  Key=stickiness.lb_cookie.duration_seconds,Value=86400 Key=deregistration_delay.timeout_seconds,Value=30 > /dev/null
TARGETS=""
for id in "$@"; do TARGETS="$TARGETS Id=$id"; done
aws elbv2 register-targets --region $R --target-group-arn $TG --targets $TARGETS

ALB=$(aws elbv2 create-load-balancer --region $R --name cs6650-a2-alb --type application --scheme internet-facing \
  --subnets $SUBNETS --security-groups $ALB_SG --query 'LoadBalancers[0].LoadBalancerArn' --output text)
# WebSocket needs no switch on an ALB (it passes the Upgrade through); idle timeout above 60 s so a quiet
# WebSocket connection isn't cut
aws elbv2 modify-load-balancer-attributes --region $R --load-balancer-arn $ALB \
  --attributes Key=idle_timeout.timeout_seconds,Value=120 > /dev/null
aws elbv2 create-listener --region $R --load-balancer-arn $ALB --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TG > /dev/null

echo "target group: $TG"
echo "ALB DNS: $(aws elbv2 describe-load-balancers --region $R --load-balancer-arns $ALB --query 'LoadBalancers[0].DNSName' --output text)"
