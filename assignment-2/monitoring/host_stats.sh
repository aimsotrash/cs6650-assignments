#!/bin/bash
# CPU, memory and network of this host every 5 s, as CSV on stdout (run it on each EC2 host during a test):
#   bash host_stats.sh 300 > stats_$(hostname -s).csv
# columns: time, cpu busy % (user+system), cpu steal %, memory used MB, network in / out KB/s
SECONDS_TO_RUN=${1:-300}
IFACE=$(ip route show default | awk '{print $5; exit}')
echo "time,cpu_busy_pct,cpu_steal_pct,mem_used_mb,net_in_kbps,net_out_kbps"
read -r rx0 tx0 < <(awk -v i="$IFACE:" '$1==i {print $2, $10}' /proc/net/dev)
end=$(( $(date +%s) + SECONDS_TO_RUN ))
while [ "$(date +%s)" -lt "$end" ]; do
  # vmstat 5 2: the second line is the average over the 5 s interval
  read -r us sy st < <(vmstat 5 2 | tail -1 | awk '{print $13, $14, $17}')
  read -r rx1 tx1 < <(awk -v i="$IFACE:" '$1==i {print $2, $10}' /proc/net/dev)
  mem=$(free -m | awk '/^Mem:/ {print $3}')
  echo "$(date +%T),$((us + sy)),$st,$mem,$(( (rx1 - rx0) / 5120 )),$(( (tx1 - tx0) / 5120 ))"
  rx0=$rx1; tx0=$tx1
done
