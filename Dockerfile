# Copyright (c) 2022 Institute of Software Chinese Academy of Sciences (ISCAS)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

####
# This Dockerfile is used in order to build a container that runs the Quarkus application in JVM mode
#
# Before building the container image run:
#
# ./mvnw package
#
# Then, build the image with:
#
# docker build -f src/main/docker/Dockerfile.jvm -t eulix/eulixplatform-services-jvm .
#
# Then run the container using:
#
# docker run -i --rm -p 8080:8080 eulix/eulixplatform-services-jvm
#
# If you want to include the debug port into your docker image
# you will have to expose the debug port (default 5005) like this :  EXPOSE 8080 5005
#
# Then run the container using :
#
# docker run -i --rm -p 8080:8080 -p 5005:5005 -e JAVA_ENABLE_DEBUG="true" eulix/eulixplatform-services-jvm
#
###
FROM registry.eulix.xyz/collab/infra/infrastructure/ubi8/ubi-minimal:8.3

ARG CI_COMMIT_SHA
ARG CI_PIPELINE_ID
ENV CI_COMMIT_SHA=${CI_COMMIT_SHA}
ENV CI_PIPELINE_ID=${CI_PIPELINE_ID}

ARG JAVA_PACKAGE=java-11-openjdk-headless
ARG RUN_JAVA_VERSION=1.3.8
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en'
# Install java and the run-java script
# Also set up permissions for user `1001`
RUN microdnf install curl ca-certificates ${JAVA_PACKAGE} \
    && microdnf clean all \
    && mkdir /deployments \
    && chown 1001 /deployments \
    && chmod "g+rwX" /deployments \
    && chown 1001:root /deployments \
    && curl https://repo1.maven.org/maven2/io/fabric8/run-java-sh/${RUN_JAVA_VERSION}/run-java-sh-${RUN_JAVA_VERSION}-sh.sh -o /deployments/run-java.sh \
    && chown 1001 /deployments/run-java.sh \
    && chmod 540 /deployments/run-java.sh \
    && echo "securerandom.source=file:/dev/urandom" >> /etc/alternatives/jre/conf/security/java.security \
    && microdnf install fontconfig

# Configure the JAVA_OPTIONS, you can add -XshowSettings:vm to also display the heap size.
ENV JAVA_OPTIONS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager -Duser.timezone=GMT+08"
# We make four distinct layers so if there are application changes the library layers can be re-used
COPY --chown=1001 eulixplatform-opstage/target/quarkus-app/lib/ /deployments/lib/
COPY --chown=1001 eulixplatform-opstage/target/quarkus-app/*.jar /deployments/
COPY --chown=1001 eulixplatform-opstage/target/quarkus-app/app/ /deployments/app/
COPY --chown=1001 eulixplatform-opstage/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 1001

ENTRYPOINT [ "/deployments/run-java.sh" ]