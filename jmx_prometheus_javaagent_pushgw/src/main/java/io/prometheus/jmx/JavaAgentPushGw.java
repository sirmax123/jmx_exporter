package io.prometheus.jmx;

import java.io.File;
import java.io.*;
import java.lang.System;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.PushGateway;
import io.prometheus.client.hotspot.DefaultExports;

// Based on JavaAgent class (see jmx_prometheus_javaagent folder)
public class JavaAgentPushGw {

    static class MainThread extends Thread  {

        private Config config;
        private int sleepInterval;
        private List<String> extraLabelNames = new ArrayList<>();
        private List<String> extraLabelValues = new ArrayList<>();
        private final static int SECONDS_PER_MILLISECOND = 1000;
        
        MainThread(Config config, List<String> extraLabelNames, List<String> extraLabelValues) {
            this.config = config;
            this.extraLabelNames = extraLabelNames;
            this.extraLabelValues = extraLabelValues;
            this.sleepInterval = SECONDS_PER_MILLISECOND * this.config.interval;

        }

        public void run() {
            try {

                // Agent is designed to be used with dynamically-created processes like Apache Storm
                // topology. So, in general case, it is not possible to know on which node in cluster
                // process will be started.
                // And all process are having same agent configuration, provided on per-cluster level,
                // not on per-process level.
                // So, the only way I see to identify process in Apache Storm is to use PID and hostname of node.
                final String jvmPidAtHost = ManagementFactory.getRuntimeMXBean().getName();
                final String jvmPid = jvmPidAtHost.split("@")[0];
                final String jvmHost = jvmPidAtHost.split("@")[1];

                extraLabelNames.add("jvm_pid");
                extraLabelValues.add(jvmPid);
                extraLabelNames.add("jvm_host");
                extraLabelValues.add(jvmHost);

                new BuildInfoCollector().register();
                new JmxCollector(new File(config.file), extraLabelNames, extraLabelValues).register();
                DefaultExports.initialize();
                PushGateway pg = new PushGateway(config.host + ":" + config.port);

                while (true) {
                    try {
                        pg.pushAdd(CollectorRegistry.defaultRegistry, jvmHost + "_" +  jvmPid);
                        Thread.sleep(this.sleepInterval);
                    } catch (InterruptedException e) {
                        System.err.println(e.getMessage());
                        System.exit(1);
                    } catch (IOException e) {
                        System.err.println(e.getMessage() + " PushGateway: " + config.host + ":" + config.port);
                        Thread.sleep(this.sleepInterval * 3);
                        
                    }
                }
            } catch (Exception e){
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }
    }

    public static void agentmain(String agentArgument, Instrumentation instrumentation) throws Exception {
        premain(agentArgument, instrumentation);
    }

    public static HashMap<String, List> getJVMLabels(String[] allowedJVMLabelPrefixesList) {

        HashMap<String, List> jvmPropertiesLabelNamesValues = new HashMap<String, List>();
        List<String> labelNames = new ArrayList<>();
        List<String> labelValues = new ArrayList<>();

        Properties jvmProperties = System.getProperties();
        Set<String> keys = jvmProperties.stringPropertyNames();

        String labelName = new String();
        String labelValue = new String();

        for ( Map.Entry<Object, Object> entry : jvmProperties.entrySet() ) {

            labelName =entry.getKey().toString();
            labelValue =entry.getValue().toString();

            // if list of allowed label prefixes is not empty, add only labels which
            // starts with prefix.
            if (allowedJVMLabelPrefixesList.length > 0 ) {
                for ( String allowedJVMLabelPrefix : allowedJVMLabelPrefixesList) {
                    if ( labelName.startsWith(allowedJVMLabelPrefix) ) {
                        labelNames.add(labelName.replace(".", "__").replace(" ", "_"));
                        labelValues.add(labelValue.replace(".", "__").replace(" ", "_"));
                        // stop scanning after first match
                        break;
                    }
                }
            } else {
                labelNames.add(labelName.replace(".", "__").replace(" ", "_"));
                labelValues.add(labelValue.replace(".", "__").replace(" ", "_"));
            }
        }

        jvmPropertiesLabelNamesValues.put("Names", labelNames);
        jvmPropertiesLabelNamesValues.put("Values", labelValues);

        return jvmPropertiesLabelNamesValues;
    }


    public static void premain(String agentArgument, Instrumentation instrumentation) throws Exception {
        
        try {
            Config config = parseConfig(agentArgument);

            List<String> extraLabelNames = new ArrayList<>();
            List<String> extraLabelValues = new ArrayList<>();

            HashMap<String, List> jvmPropertiesLabelNamesValues = new HashMap<String, List>();

            Properties jvmProperties = System.getProperties();
            Set<String> keys = jvmProperties.stringPropertyNames();

            // Check all extra option and add additional labels if configured
            String allowedJVMLabelPrefixes = new String();
            if ( config.extraSettings.containsKey("allowedJVMLabelPrefixes") ) {
                allowedJVMLabelPrefixes = config.extraSettings.get("allowedJVMLabelPrefixes");
            }
            // List of allowed label frefixes
            String[] allowedJVMLabelPrefixesList = allowedJVMLabelPrefixes.split(",");


            // Add jvm labels after filtering
            if ( config.extraSettings.containsKey("jvmLabels") ) {
                if (config.extraSettings.get("jvmLabels").toLowerCase().equals("true") ) {
                    jvmPropertiesLabelNamesValues = getJVMLabels(allowedJVMLabelPrefixesList);

                    extraLabelNames.addAll(jvmPropertiesLabelNamesValues.get("Names"));
                    extraLabelValues.addAll(jvmPropertiesLabelNamesValues.get("Values"));
                }
            }


            // Add labels with value =  true for extraTrueLabels
            String extraTrueLabels = new String();
            if ( config.extraSettings.containsKey("extraTrueLabels") ) {
                extraTrueLabels = config.extraSettings.get("extraTrueLabels");
            }

            String[] extraTrueLabelsList = extraTrueLabels.split(",");
            if ( extraTrueLabelsList.length > 0 ) {
                for ( String extraTrueLabel : extraTrueLabelsList ) {
                    if ( extraTrueLabel.length() > 0 ) {
                        extraLabelNames.add(extraTrueLabel);
                        extraLabelValues.add("true");
                    }
                }
            }

            //System.err.println("Labels added to all metrinc are:");
            //System.err.println(extraLabelNames);

            Thread bgThread = new MainThread(config, extraLabelNames, extraLabelValues);
            bgThread.start();

        } catch (IllegalArgumentException e) {
            System.err.println("Usage: -javaagent:/path/to/JavaAgent.jar=[host:]<port>:<yaml configuration file>:<extra options> \n" +
                               "Usage example:   -javaagent:./jmx_prometheus_javaagent_pushgw.jar=127.0.0.1:9092:config.yml:55:jvmLabels=true:allowedJVMLabelPrefixes=storm,java:addLabel=staging,openstack \n" +
                               "In example:" +
                               "    Path the to jar file: ./jmx_prometheus_javaagent_pushgw.jar \n" +
                               "    Push GW address and port: 127.0.0.1:9091 (only HTTP is supported now!) \n" +
                               "    Path to the config file: config.yaml \n" +
                               "    Interval between sending data: 55 \n" +
                               "    jvmLabels option set to true is adding JVM parameter \n" +
                               "    allowedJVMLabelPrefixes option filters jvmLabels, in this example are allowed only started with 'staging' or 'openstack'");
            System.err.println(e.getMessage());
            System.exit(1);
        }



    }

    public static Config parseConfig(String args) {
        Pattern pattern = Pattern.compile(
                "^(?:((?:[\\w.]+)|(?:\\[.+])):)?" +  // host name, or ipv4, or ipv6 address in brackets
                        "(\\d{1,5}):" +              // port
                        "(.+):" +                    // config file
                        "(\\d{1,5})" +              // interval
                        "(.*)");                    // Extra settings

        Matcher matcher = pattern.matcher(args);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed arguments - " + args);
        }

        String givenHost = matcher.group(1);
        String givenPort = matcher.group(2);
        String givenConfigFile = matcher.group(3);
        String givenInterval = matcher.group(4);
        String givenExtraSettings = matcher.group(5);

        int port = Integer.parseInt(givenPort);
        int interval = Integer.parseInt(givenInterval);

        return new Config(givenHost, port, givenConfigFile, interval, givenExtraSettings);
    }

    static class Config {
        String host;
        int port;
        String file;
        int interval;
        HashMap<String, String> extraSettings;

        Config(String host, int port, String file, int interval, String extraSettings) {
            this.host = host;
            this.port = port;
            this.file = file;
            this.interval = interval;

            final String[] extraSettingsOptions = extraSettings.split(":");

            HashMap<String, String> extraSettingsMap = new HashMap<String, String>();
            for( String extraSettingsOption : extraSettingsOptions ) {
                String[] optionWithValue = extraSettingsOption.split("=");
                if ( optionWithValue.length > 1 ) {
                    extraSettingsMap.put(optionWithValue[0], optionWithValue[1]);
                } else {
                    extraSettingsMap.put(optionWithValue[0], "true");
                }
            }

            this.extraSettings = extraSettingsMap;
        }
    }
}
