# logback-redis-appender Hardis

This project is a clone of https://github.com/hardisgroupcom/log4j-redis-appender which was subsequently adapted to operate with logback. 

## Configuration

This appender pushes logback events to a Redis list. Here is an example XML configuration:

```
  <appender name="TEST" class="com.hardis.logback.FailoverRedisAppender">    
    <endpoints>vm1.hardis.fr:6379,vm2.hardis.fr:6565</endpoints>
    <!-- <host>vmlnxdocker.hardis.fr</host>
    <port>6565</port> -->
    <key>key1</key>
    <layout class="net.logstash.logback.layout.LogstashLayout"></layout>    
    <alwaysBatch>true</alwaysBatch>
    <registerMBean>false</registerMBean>  
    <queueSize>5000</queueSize>
    <flushInterval>500</flushInterval>
    <batchSize>100</batchSize>
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

When the appender's MBean is registered (see `registerMBean` configuration) the following metrics are available under MBean "com.hardis.logback:type=FailoverRedisAppender":

* **eventCounter**: (counter) number of events received by the appender and put in the queue. Configuration `queueSize` controls the the maximum number of events this queue can hold.
* **eventsDroppedInQueueing**: (counter) number of events that got dropped, because the queue was full. You get a full queue if Redis is full or your application is emitting events faster than can be pushed to Redis. You can increase `queueSize` or rise the log4j threshold to ignore events based on their level (DEBUG can be noisy).
* **eventsDroppedInPush**: (counter) number of events that got dropped, because the Redis' memory is full and responds to `RPUSH` commands with an OOM error.
* **connectCounter**: (counter)number of connects made to the Redis server(s).
* **connectFailures**: (counter) number of connect attempts that failed.
* **batchPurges**: (counter) number of times the purge queue got purged. This only happens if `purgeOnFailure` is set to true.
* **eventsPushed**: (counter) number of events succesfully pushed to Redis.
* **eventQueueSize**: (gauge) number of events in the queue. When reading this attribute you get a sampled value. Use this to get a feel about the average number of queued events. When this gets above 50% of `queueSize` you may want to investigate a slow Redis server or silly amounts of events being emitted by your application.



## JCollectd configuration

Use [Jcollectd](https://github.com/hardisgroupcom/jcollectd) in your JVM to periodically flush MBean metrics to [collectd](https://github.com/collectd/collectd) or [Diamond](https://github.com/BrightcoveOS/Diamond)'s [JCollectdCollector](https://github.com/BrightcoveOS/Diamond/wiki/collectors-JCollectdCollector).

Jcollectd XML config for this appender:

```
<jcollectd-config>
  <mbeans name="log4j">
    <mbean name="com.hardis.logback:type=FailoverRedisAppender" alias="RedisAppender">
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

# Contribution

Feel free to create an issue or submit a pull request.


# License

Published under Apache Software License 2.0, see LICENSE

