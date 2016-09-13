# logback-redis-appender

Ce projet part d'un clone de https://github.com/bolcom/log4j-redis-appender qui a été ensuite adapté pour fonctionner avec logback. 

Des mini ajustement ont été fait pour en faire un projet Hardis:
* Nom des packages
* Supression du fichier de licence
* Modification du pom.xml pour le plus inclure les jar dépendants dans le jar de ce projet

Ensuite pour en faire un projet logback les modifications sont les suivantes:
* Dépendance vers ch.qos.logback.logback-classic
* Le RedisAppender étend UnsynchronizedAppenderBase et implémente les méthode abstraite start, stop et append
* Ajout de la variable layout pour permettre dans le paramétrage de choisir un layout autre que celui par défaut dans ce projet 
* La documentation a été réécrite


# logback-redis-appender

logback appender for pushing logback events to a Redis list, for easy integration with Logstash.

It also protects the JVM against OOM's from too many buffered events. And it exposes metrics about its throughput and data loss.

The code is based from [@ryantenney's work](https://github.com/ryantenney/log4j-redis-appender), which in turn was derived from [@pavlobaron's log4j2redis](https://github.com/pavlobaron/log4j2redis) work.

The main differences compared to Ryan's great work:

* it drops events to protect the JVM against OOM's
* it detects Redis OOM's
* it keeps metrics (about throughput + drops),

This appender works great with [log4j-jsonevent-layout](https://github.com/bolcom/log4j-jsonevent-layout) to transform all events into JSON documents, for easy processing by Logstash.


## Configuration

This appender pushes log4j events to a Redis list. Here is an example XML configuration:

```
   <appender name="JSON_REDIS" class="com.bol.log4j.FailoverRedisAppender">
      <param name="endpoints" value="server1:6379,server2:6379" />
      <param name="alwaysBatch" value="false" />
      <param name="batchSize" value="50" />
      <param name="flushInterval" value="1000" />
      <param name="queueSize" value="5000" />
      <param name="registerMBean" value="true" />
      <param name="key" value="logstash.log4j" />
      <param name="Threshold" value="DEBUG"/>
      <layout class="net.logstash.log4j.JSONEventLayout">
         <param name="userfields" value="application:xyz,role:xyz-app"/>
      </layout>
   </appender>
```
   
Where:

* **endpoints** (_optional_) comma separated list of Redis servers in format <host>:<port>. The list is shuffled at startup. When connecting or reconnecting the next server on the list is used.
* **host** + **port** (_optional_, default: localhost:6379) Hostname/IP and port number of a single Redis server. Use this OR `endpoints` to configure the Redis server(s) used.
* **key** (_required_) Redis key of the list to `RPUSH` events to.
* **password** (_optional_) Redis password, if required.
* **alwaysBatch** (_optional_, default: true) whether to wait for a full batch. If true, will only send once there are `batchSize` events enqueued.
* **batchSize** (_optional_, default: 100) the number of events to send in a single Redis `RPUSH` command.
* **flushInterval** (_optional_, default: 500) the period in milliseconds between flush attempts. If events are flushed depends on the 'alwaysBatch' setting and the number of events in the buffer.
* **queueSize** (_optional_, default: 5000) the maximum number of events the appender holds in memory, awaiting flush. If flushing is not possible, or is too slow the queue will slowly fill up. When the queue is full, new events will be dropped to protect the JVM.
* **purgeOnFailure** (_optional_, default: true) whether to purge/drop events if Redis responds to a `RPUSH` with an OOM error. If 'false' the appender will attempt to send the events to Redis. If that keeps failing the queue will slowly fill up and new events will be dropped.
* **registerMBean** (_optional_, default: true) whether to expose the appender's metrics as MBean.


## Metrics

When the appender's MBean is registered (see `registerMBean` configuration) the following metrics are available under MBean "com.bol.log4j:type=FailoverRedisAppender":

* **eventCounter**: (counter) number of events received by the appender and put in the queue. Configuration `queueSize` controls the the maximum number of events this queue can hold.
* **eventsDroppedInQueueing**: (counter) number of events that got dropped, because the queue was full. You get a full queue if Redis is full or your application is emitting events faster than can be pushed to Redis. You can increase `queueSize` or rise the log4j threshold to ignore events based on their level (DEBUG can be noisy).
* **eventsDroppedInPush**: (counter) number of events that got dropped, because the Redis' memory is full and responds to `RPUSH` commands with an OOM error.
* **connectCounter**: (counter)number of connects made to the Redis server(s).
* **connectFailures**: (counter) number of connect attempts that failed.
* **batchPurges**: (counter) number of times the purge queue got purged. This only happens if `purgeOnFailure` is set to true.
* **eventsPushed**: (counter) number of events succesfully pushed to Redis.
* **eventQueueSize**: (gauge) number of events in the queue. When reading this attribute you get a sampled value. Use this to get a feel about the average number of queued events. When this gets above 50% of `queueSize` you may want to investigate a slow Redis server or silly amounts of events being emitted by your application.



## JCollectd configuration

Use [Jcollectd](https://github.com/bolcom/jcollectd) in your JVM to periodically flush MBean metrics to [collectd](https://github.com/collectd/collectd) or [Diamond](https://github.com/BrightcoveOS/Diamond)'s [JCollectdCollector](https://github.com/BrightcoveOS/Diamond/wiki/collectors-JCollectdCollector).

Jcollectd XML config for this appender:

```
<jcollectd-config>
  <mbeans name="log4j">
    <mbean name="com.bol.log4j:type=FailoverRedisAppender" alias="RedisAppender">
      <attribute name="ConnectCounter" type="counter"/>
      <attribute name="ConnectFailures" type="counter"/>
      <attribute name="EventCounter" type="counter"/>
      <attribute name="EventsPushed" type="counter"/>
      <attribute name="EventsDroppedInPush" type="counter"/>
      <attribute name="EventsDroppedInQueueing" type="counter"/>
      <attribute name="EventQueueSize" />
      <attribute name="BatchPurges" type="counter"/>
    </mbean>
  </mbeans>
</jcollectd-config>
```
