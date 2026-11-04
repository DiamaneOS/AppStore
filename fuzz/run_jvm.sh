#!/bin/sh
# Builds the JVM harness, makes the seeds and runs each Jazzer target in parallel:
# run_jvm.sh <deps dir> <work dir> <seconds per target> [target...]
# The deps dir holds deps.lock's files (tests/jvm/fetch_deps.py). A time of 0 only replays the corpus.
set -eu
deps=$(realpath "$1")
work=$(realpath -m "$2")
time=$3
shift 3
here=$(cd "$(dirname "$0")" && pwd)
targets=${*:-catalog_bytes signed_catalog catalog_sequence install_flow transport cache}
python3 -I "$here/../tests/jvm/fetch_deps.py" "$deps"
python3 -I "$here/../tests/jvm/build.py" "$deps" "$work/build"
build=$work/build
java=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}/bin/java
opts="-Xss4m -Xmx3g -Djava.library.path=$build/native -Dfixture.key=$build/test-key.bin -Dfixture.files=$build/fixtures"
opts="$opts --add-opens java.base/java.io=ALL-UNNAMED"
cp="$(cat "$build/classpath.txt"):$deps/jazzer-0.30.0.jar"
mkdir -p "$work/seeds-root"
$java $opts -Dfixture.root="$work/seeds-root" -cp "$cp" harness.SeedsKt "$work/seeds"
for t in $targets; do
    case $t in
        catalog_bytes) class=CatalogBytesFuzzer ;;
        signed_catalog) class=SignedCatalogFuzzer ;;
        catalog_sequence) class=CatalogSequenceFuzzer ;;
        install_flow) class=InstallFlowFuzzer ;;
        transport) class=TransportFuzzer ;;
        cache) class=CacheFuzzer ;;
        *) echo "unknown target $t" >&2; exit 1 ;;
    esac
    mkdir -p "$work/corpus/$t" "$work/artifacts/$t" "$work/root/$t"
    if [ "$time" = 0 ]; then limit="-runs=0"; log=$work/$t.replay.log; else limit="-max_total_time=$time"; log=$work/$t.log; fi
    $java $opts -Dfixture.root="$work/root/$t" -cp "$cp" com.code_intelligence.jazzer.Jazzer \
        --target_class=harness.$class \
        --instrumentation_includes='app.grapheneos.**:de.diamaneos.**:org.json.**:org.bouncycastle.util.encoders.**:app.accrescent.**' \
        --reproducer_path="$work/artifacts/$t" \
        $limit -print_final_stats=1 -rss_limit_mb=6144 -max_len=65536 -timeout=120 \
        -artifact_prefix="$work/artifacts/$t/" "$work/corpus/$t" "$work/seeds/$t" > "$log" 2>&1 &
done
wait
