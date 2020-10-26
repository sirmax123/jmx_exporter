package io.prometheus.jmx;

import org.junit.Assert;
import org.junit.Test;

public class TestJavaAgent {
    /**
     * Test that the agent string argument is parsed properly. We expect the agent argument in one of these forms...
     * <pre>
     * {@code <port>:<yaml configuration file>}
     * </pre>
     * <pre>
     * {@code <host>:<port>:<yaml configuration file>}
     * </pre>
     * Since the ':' character is part of the spec for this arg, Windows-style paths could cause an issue with parsing.
     * See https://github.com/prometheus/jmx_exporter/issues/312.
     */
    @Test
    public void testAgentStringParsing() {
        final String DEFAULT_HOST = "127.0.0.1";

        JavaAgentPushGw.Config config = JavaAgentPushGw.parseConfig("localhost:8080:config.yaml:60");

        config = JavaAgentPushGw.parseConfig("google.com:8080:/unix/path/config.yaml:60");
        Assert.assertEquals("google.com", config.host);
        Assert.assertEquals("/unix/path/config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgentPushGw.parseConfig("127.0.0.1:8080:/unix/path/config.yaml:60");
        Assert.assertEquals("127.0.0.1", config.host);
        Assert.assertEquals("/unix/path/config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        // the following check was previously failing to parse the file correctly
        config = JavaAgentPushGw.parseConfig("google.com:8080:C:\\Windows\\Path\\config.yaml:60");
        Assert.assertEquals("google.com", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        // the following check was previously failing to parse the file correctly
        config = JavaAgentPushGw.parseConfig("127.0.0.1:8080:C:\\Windows\\Path\\config.yaml:60");
        Assert.assertEquals("127.0.0.1", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);
    }

    /**
     * If someone is specifying an ipv6 address and a host name, this should be rejected.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidInput() {
        JavaAgentPushGw.parseConfig("[2001:0db8:0000:0042:0000:8a2e:0370:7334]:localhost:8080:config.yaml:60");
    }

    /**
     * Similarly to the test above, two host names
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidInput2() {
        JavaAgentPushGw.parseConfig("127.0.0.1:localhost:8080:config.yaml:60");
    }

    /**
     * Exercise the existing Ipv6 parsing logic. The current logic leaves the brackets on the host.
     */
    @Test
    public void testIpv6AddressParsing() {
        final String DEFAULT_HOST = "0.0.0.0";

        JavaAgentPushGw.Config config = JavaAgentPushGw.parseConfig("[1:2:3:4]:8080:config.yaml:60");
        Assert.assertEquals("[1:2:3:4]", config.host);
        Assert.assertEquals("config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgentPushGw.parseConfig("[2001:0db8:0000:0042:0000:8a2e:0370:7334]:8080:C:\\Windows\\Path\\config.yaml:60");
        Assert.assertEquals("[2001:0db8:0000:0042:0000:8a2e:0370:7334]", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);
    }
}
