package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigUiResourceStructureTest {
    private static final Pattern LOCAL_RESOURCE = Pattern.compile(
            "(?:href|src)=\"/(assets/(?:css|js)/[^\"]+)\"");

    @Test
    public void keepsMarkupThinAndLoadsEveryModuleInDependencyOrder() throws Exception {
        String html = ConfigUiTestResources.load("configui/index.html");
        assertTrue(html.length() < 100_000);
        assertFalse(html.contains("<style>"));
        assertFalse(Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>")
                .matcher(html).find());

        List<String> expected = Arrays.asList(
                "assets/js/theme.js",
                "assets/css/base.css",
                "assets/css/channels.css",
                "assets/css/starweave.css",
                "assets/css/tasks.css",
                "assets/css/resources.css",
                "assets/css/responsive.css",
                "assets/css/dark-theme.css",
                "assets/css/schedules.css",
                "assets/css/observations.css",
                "assets/js/core.js",
                "assets/js/registry.js",
                "assets/js/mcp-auth.js",
                "assets/js/providers.js",
                "assets/js/chat-history.js",
                "assets/js/starweave.js",
                "assets/js/resources.js",
                "assets/js/channels.js",
                "assets/js/agents.js",
                "assets/js/tasks.js",
                "assets/js/schedules.js",
                "assets/js/observations.js",
                "assets/js/ui.js",
                "assets/js/app.js");
        Matcher matcher = LOCAL_RESOURCE.matcher(html);
        int index = 0;
        while (matcher.find()) {
            assertTrue("unexpected extra resource " + matcher.group(1), index < expected.size());
            assertTrue("resource order mismatch at " + index,
                    expected.get(index).equals(matcher.group(1)));
            assertFalse(ConfigUiTestResources.load("configui/" + matcher.group(1)).isEmpty());
            index++;
        }
        assertTrue("missing resource references", index == expected.size());
    }

    @Test
    public void keepsDomainLogicInItsNamedModule() throws Exception {
        assertTrue(ConfigUiTestResources.load("configui/assets/js/channels.js")
                .contains("function renderChannels()"));
        assertTrue(ConfigUiTestResources.load("configui/assets/js/starweave.js")
                .contains("async function loadStarweaveSessions("));
        assertTrue(ConfigUiTestResources.load("configui/assets/js/tasks.js")
                .contains("async function taskApi("));
        assertTrue(ConfigUiTestResources.load("configui/assets/js/schedules.js")
                .contains("async function scheduleApi("));
        assertTrue(ConfigUiTestResources.load("configui/assets/css/schedules.css")
                .contains(".schedule-list-head>:last-child{text-align:left}"));
        assertTrue(ConfigUiTestResources.load("configui/assets/css/schedules.css")
                .contains(".schedule-row-actions{display:flex;justify-content:flex-start"));
    }
}
