package com.hardis.logback;

import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * FailoverRedisAppender uses a shuffled list of host:port pairs from which it uses the first available.
 *
 */
public class FailoverRedisAppender extends RedisAppender {

	final static Logger logger = LoggerFactory.getLogger(FailoverRedisAppender.class);
	
    // Settings
    private String endpoints = null; // comma separated host:port pairs

    public static class HostPort {
        String host;
        int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private HostPort[] shuffled;
    private int shuffleIndex = 0;

    @Override
    public void activateOptions() {    	
        try {
            if (endpoints == null || endpoints.length() == 0){            	
            	throw new IllegalStateException("Must set 'endpoints'");	
            }                
            String[] endpointList = this.endpoints.split(",");
            // create HostPort list
            this.shuffled = new HostPort[endpointList.length];
            for (int h = 0; h < endpointList.length; h++) {
                String[] hostport = endpointList[h].split(":");
                this.shuffled[h] = new HostPort(hostport[0], Integer.parseInt(hostport[1]));
            }
            this.shuffle(shuffled);
            super.activateOptions();
        } catch (Exception e) {
        	logger.error("Error during activateOptions", e);
        }
    }

    /**
     * Fisher–Yates shuffle
     * @param hplist
     */
    private void shuffle(HostPort[] hplist) {
        Random rnd = new Random();
        for (int i = hplist.length - 1; i > 0; i--) {
            int index = rnd.nextInt(i + 1);
            // Simple swap
            HostPort a = hplist[index];
            hplist[index] = hplist[i];
            hplist[i] = a;
        }
    }

    @Override
    protected void createJedis() {
        HostPort hp = this.shuffled[this.shuffleIndex];
        this.setHost(hp.host);
        this.setPort(hp.port);
        super.createJedis();
    }

    @Override
    protected synchronized boolean connect() {
        // See if the first succeeds or we are still connected.
        if (super.connect()) {
            return true;
        }
        // change the endpoint config to the next
        int first = this.shuffleIndex;
        this.createJedis();
        while (true) {
            // again,attempt a connect and break if successful
            if (super.connect()) {
                return true;
            }
            logger.debug("Connect failed, trying the next");
            // rotate for the next
            this.shuffleIndex = (this.shuffleIndex + 1) % this.shuffled.length;

            // i tried them all
            if (first == this.shuffleIndex) {
            	 logger.debug("Connect failed, no more hosts to try");
                return false;
            } else {
                this.createJedis();
            }
        }
    }

    public String getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(String endpoints) {
        this.endpoints = endpoints;
    }
}
