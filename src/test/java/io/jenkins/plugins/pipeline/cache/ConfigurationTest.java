package io.jenkins.plugins.pipeline.cache;


import org.htmlunit.html.HtmlForm;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ConfigurationTest {

    @Rule
    public JenkinsSessionRule rule = new JenkinsSessionRule();

    @Test
    public void testConfiguration() throws Throwable {
        // WHEN
        rule.then(r -> {
            // THEN
            assertNull("should be empty initially", CacheConfiguration.get().getUsername());
            assertNull("should be empty initially", CacheConfiguration.get().getPassword());
            assertNull("should be empty initially", CacheConfiguration.get().getBucket());
            assertNull("should be empty initially", CacheConfiguration.get().getRegion());
            assertNull("should be empty initially", CacheConfiguration.get().getEndpoint());
            assertEquals(0, CacheConfiguration.get().getThreshold());

            // WHEN
            try (JenkinsRule.WebClient c = r.createWebClient()) {
                HtmlForm config = c.goTo("manage/configure").getFormByName("config");
                config.getInputByName("_.username").setValueAttribute("alice");
                config.getInputByName("_.password").setValueAttribute("secret");
                config.getInputByName("_.bucket").setValueAttribute("blue");
                config.getInputByName("_.region").setValueAttribute("dc1");
                config.getInputByName("_.endpoint").setValueAttribute("http://localhost:9000");
                config.getInputByName("_.threshold").setValueAttribute(Long.toString(777));
                r.submit(config);
            }

            // THEN
            assertEquals("should be editable", "alice", CacheConfiguration.get().getUsername());
            assertEquals("should be editable", "secret", CacheConfiguration.get().getPassword().getPlainText());
            assertEquals("should be editable", "blue", CacheConfiguration.get().getBucket());
            assertEquals("should be editable", "dc1", CacheConfiguration.get().getRegion());
            assertEquals("should be editable", "http://localhost:9000", CacheConfiguration.get().getEndpoint());
            assertEquals("should be editable", 777, CacheConfiguration.get().getThreshold());

        });
        // WHEN
        rule.then(r -> {
            // THEN
            assertEquals("should be still there after restart of Jenkins", "alice", CacheConfiguration.get().getUsername());
            assertEquals("should be still there after restart of Jenkins", "secret", CacheConfiguration.get().getPassword().getPlainText());
            assertEquals("should be still there after restart of Jenkins", "blue", CacheConfiguration.get().getBucket());
            assertEquals("should be still there after restart of Jenkins", "dc1", CacheConfiguration.get().getRegion());
            assertEquals("should be still there after restart of Jenkins", "http://localhost:9000", CacheConfiguration.get().getEndpoint());
            assertEquals("should be still there after restart of Jenkins", 777, CacheConfiguration.get().getThreshold());
        });
    }

}
