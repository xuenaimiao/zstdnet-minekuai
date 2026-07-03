package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthlibInjectorDetectorTest {

    @Test
    void extractsApiRootFromJavaagentArg() {
        assertEquals("https://littleskin.cn/api/yggdrasil", AuthlibInjectorDetector.extractApiRoot(Arrays.asList(
            "-Xmx4G",
            "-javaagent:authlib-injector-1.2.5.jar=https://littleskin.cn/api/yggdrasil",
            "-Dfile.encoding=UTF-8")));
    }

    @Test
    void extractsFromAbsolutePathsAndStripsTrailingSlash() {
        assertEquals("https://skin.example.com/api/yggdrasil", AuthlibInjectorDetector.extractApiRoot(Collections.singletonList(
            "-javaagent:C:\\server\\tools\\authlib-injector.jar=https://skin.example.com/api/yggdrasil/")));
        assertEquals("https://skin.example.com/api/yggdrasil", AuthlibInjectorDetector.extractApiRoot(Collections.singletonList(
            "-javaagent:/opt/server/authlib-injector-1.2.5.jar=https://skin.example.com/api/yggdrasil")));
    }

    @Test
    void ignoresOtherAgentsAndAgentWithoutUrl() {
        assertNull(AuthlibInjectorDetector.extractApiRoot(Arrays.asList(
            "-javaagent:some-other-agent.jar=whatever",
            "-javaagent:authlib-injector.jar",
            "-javaagent:authlib-injector.jar=")));
        assertNull(AuthlibInjectorDetector.extractApiRoot(Collections.emptyList()));
        assertNull(AuthlibInjectorDetector.extractApiRoot(null));
    }
}
