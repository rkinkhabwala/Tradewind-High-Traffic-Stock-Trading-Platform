#!/usr/bin/env bash
# Load-test runner (Phase 3). Runs against the RUNNING compose stack.
#
#   scripts/load.sh setup [-Dload.users=1000]       create/fund the load users (once; safe to re-run)
#   scripts/load.sh check                           correctness check only
#   scripts/load.sh <SimulationName> [-Dload.k=v]   run a Gatling simulation, then the correctness check
#
# Exits non-zero if the simulation's assertions or the correctness check fail. Reports: load-tests/target.nosync/gatling/
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export JAVA_HOME="${JAVA_HOME_21:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

what="${1:?usage: scripts/load.sh setup|check|<SimulationName> [-Dload.key=value ...]}"
shift
mvn -q -o install -DskipTests -pl load-tests -am > /dev/null

run_main() {
  mvn -q -o -pl load-tests exec:java -Dexec.mainClass="com.tradewind.load.$1" "${@:2}"
}

case "$what" in
  setup) run_main LoadUsers "$@" ;;
  check) run_main CorrectnessCheck "$@" ;;
  *)
    status=0
    mkdir -p load-tests/target.nosync/gatling
    log="load-tests/target.nosync/gatling/$what-$(date +%Y%m%d-%H%M%S).log"
    mvn -o -pl load-tests gatling:test -Dgatling.simulationClass="com.tradewind.load.$what" "$@" 2>&1 | tee "$log" \
      || status=$?
    [[ ${PIPESTATUS[0]} -ne 0 ]] && status=1
    run_main CorrectnessCheck "$@" 2>&1 | tee -a "$log" | grep '\[correctness\]' || true
    grep -q '\[correctness\] OK' "$log" || status=1
    echo "console log: $log"
    exit $status
    ;;
esac
