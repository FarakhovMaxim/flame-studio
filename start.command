#!/bin/zsh
cd "${0:A:h}" || exit 1
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null)"
fi
./gradlew --quiet jar || { read '?Не удалось собрать приложение. Нажмите Enter…'; exit 1; }
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx2g -Dfile.encoding=UTF-8 -jar build/libs/FlameStudio-1.0.0.jar "$@"
status_code=$?
read '?Нажмите Enter, чтобы закрыть окно…'
exit "$status_code"
