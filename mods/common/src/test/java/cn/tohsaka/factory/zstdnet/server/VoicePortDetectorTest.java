package cn.tohsaka.factory.zstdnet.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicePortDetectorTest {

    // ---- Simple Voice Chat ----------------------------------------------

    @Test
    void svcIndependentPortDetected() {
        assertEquals(Integer.valueOf(24454), VoicePortDetector.parseSimpleVoiceChatPort("port=24454\n", "svc"));
    }

    @Test
    void svcSamePortSentinelSkipped() {
        assertNull(VoicePortDetector.parseSimpleVoiceChatPort("port=-1\n", "svc"));
    }

    @Test
    void svcMissingPortSkipped() {
        assertNull(VoicePortDetector.parseSimpleVoiceChatPort("codec=VOIP\n", "svc"));
    }

    @Test
    void svcExplicitVoiceHostBypassesAndSkips() {
        assertNull(VoicePortDetector.parseSimpleVoiceChatPort("port=24454\nvoice_host=play.example.com\n", "svc"));
    }

    @Test
    void svcInvalidPortSkipped() {
        assertNull(VoicePortDetector.parseSimpleVoiceChatPort("port=99999\n", "svc"));
    }

    // ---- Plasmo Voice ----------------------------------------------------

    @Test
    void plasmoIndependentHostPortDetected() {
        String toml = "[host]\nip = \"0.0.0.0\"\nport = 24455\n";
        assertEquals(Integer.valueOf(24455), VoicePortDetector.parsePlasmoVoicePort(toml, 25566, "plasmo"));
    }

    @Test
    void plasmoFollowServerPortSkipped() {
        String toml = "[host]\nport = 0\n";
        assertNull(VoicePortDetector.parsePlasmoVoicePort(toml, 25566, "plasmo"));
    }

    @Test
    void plasmoPublicPortOverridesHostPort() {
        String toml = "[host]\nport = 24455\n[host.public]\nip = \"0.0.0.0\"\nport = 24456\n";
        assertEquals(Integer.valueOf(24456), VoicePortDetector.parsePlasmoVoicePort(toml, 25566, "plasmo"));
    }

    @Test
    void plasmoExplicitPublicIpBypassesAndSkips() {
        String toml = "[host]\nport = 24455\n[host.public]\nip = \"1.2.3.4\"\n";
        assertNull(VoicePortDetector.parsePlasmoVoicePort(toml, 25566, "plasmo"));
    }

    @Test
    void tomlValueReadsCorrectSection() {
        String toml = "[host]\nport = 100\n[host.public]\nport = 200\n";
        assertEquals("100", VoicePortDetector.readTomlValue(toml, "host", "port"));
        assertEquals("200", VoicePortDetector.readTomlValue(toml, "host.public", "port"));
        assertEquals("", VoicePortDetector.readTomlValue(toml, "missing", "port"));
    }

    @Test
    void tomlCommentsStripped() {
        String toml = "[host]\nport = 24455 # the udp port\n";
        assertEquals("24455", VoicePortDetector.readTomlValue(toml, "host", "port"));
    }

    // ---- extra_udp_ports -------------------------------------------------

    @Test
    void extraPortsParsedAndInvalidSkipped() {
        assertEquals(Arrays.asList(24454, 30000), VoicePortDetector.parseExtraPorts("24454, 30000"));
        assertEquals(Arrays.asList(24454), VoicePortDetector.parseExtraPorts("24454 abc 99999"));
        assertTrue(VoicePortDetector.parseExtraPorts("").isEmpty());
        assertTrue(VoicePortDetector.parseExtraPorts(null).isEmpty());
    }

    // ---- detect(): file IO + merge + de-dup + same-port skip -------------

    @Test
    void detectMergesDedupesAndSkipsSamePort(@TempDir Path configDir) throws Exception {
        // SVC independent 24454
        Path svc = configDir.resolve("voicechat").resolve("voicechat-server.properties");
        Files.createDirectories(svc.getParent());
        Files.write(svc, "port=24454\n".getBytes(StandardCharsets.UTF_8));

        // Plasmo independent 24455
        Path plasmo = configDir.resolve("plasmovoice").resolve("config.toml");
        Files.createDirectories(plasmo.getParent());
        Files.write(plasmo, "[host]\nport = 24455\n".getBytes(StandardCharsets.UTF_8));

        // extra duplicates 24454 (deduped) + adds 30000
        List<VoicePortDetector.VoicePort> ports = VoicePortDetector.detect(configDir, 25566, "24454,30000");

        assertEquals(3, ports.size());
        assertEquals(24454, ports.get(0).port());
        assertEquals("simple_voice_chat", ports.get(0).label());
        assertEquals(24455, ports.get(1).port());
        assertEquals("plasmo_voice", ports.get(1).label());
        assertEquals(30000, ports.get(2).port());
    }

    @Test
    void detectSkipsSamePortEqualToBackendGamePort(@TempDir Path configDir) throws Exception {
        Path svc = configDir.resolve("voicechat").resolve("voicechat-server.properties");
        Files.createDirectories(svc.getParent());
        Files.write(svc, "port=25566\n".getBytes(StandardCharsets.UTF_8));

        // backendGamePort == 25566 → the SVC port is same-port, must be skipped.
        List<VoicePortDetector.VoicePort> ports = VoicePortDetector.detect(configDir, 25566, "");
        assertTrue(ports.isEmpty());
    }

    @Test
    void detectEmptyWhenNoVoiceModsPresent(@TempDir Path configDir) {
        assertTrue(VoicePortDetector.detect(configDir, 25566, "").isEmpty());
    }

    // ---- bukkit/Spigot plugin layout: plugins/<VoiceMod>/ , Plasmo dir capitalized ----

    @Test
    void detectFindsBukkitPluginLayout(@TempDir Path pluginsRoot) throws Exception {
        // SVC plugin: plugins/voicechat/voicechat-server.properties (子目录名与 mod 端一致)
        Path svc = pluginsRoot.resolve("voicechat").resolve("voicechat-server.properties");
        Files.createDirectories(svc.getParent());
        Files.write(svc, "port=24454\n".getBytes(StandardCharsets.UTF_8));

        // Plasmo plugin: plugins/PlasmoVoice/config.toml (驼峰目录名，区别于 mod 端的 plasmovoice)
        Path plasmo = pluginsRoot.resolve("PlasmoVoice").resolve("config.toml");
        Files.createDirectories(plasmo.getParent());
        Files.write(plasmo, "[host]\nport = 24455\n".getBytes(StandardCharsets.UTF_8));

        List<VoicePortDetector.VoicePort> ports = VoicePortDetector.detect(Arrays.asList(pluginsRoot), 25566, "");

        assertEquals(2, ports.size());
        assertEquals(24454, ports.get(0).port());
        assertEquals("simple_voice_chat", ports.get(0).label());
        assertEquals(24455, ports.get(1).port());
        assertEquals("plasmo_voice", ports.get(1).label());
    }
}
