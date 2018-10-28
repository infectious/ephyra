FROM clojure

RUN useradd ephyra
RUN mkdir -p /opt/ephyra/bin && chown -R ephyra:ephyra /opt/ephyra

ADD target/uberjar/ephyra.jar /opt/ephyra/bin/ephyra.jar

USER ephyra
WORKDIR /opt/ephyra

ENV SENTRY_STACKTRACE_APP_PACKAGES=ephyra

CMD java -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -jar /opt/ephyra/bin/ephyra.jar
