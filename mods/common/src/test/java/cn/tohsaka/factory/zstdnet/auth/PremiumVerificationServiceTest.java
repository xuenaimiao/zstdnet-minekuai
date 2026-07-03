package cn.tohsaka.factory.zstdnet.auth;

import cn.tohsaka.factory.zstdnet.auth.PremiumVerificationService.Decision;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PremiumVerificationServiceTest {

    private static final UUID RECORDED = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void strictAlwaysRejects() {
        assertEquals(Decision.STRICT_REJECT, PremiumVerificationService.decide(true, true, RECORDED));
        assertEquals(Decision.STRICT_REJECT, PremiumVerificationService.decide(true, false, null));
    }

    @Test
    void guardRejectsOnlyPreviouslyVerifiedNames() {
        // 曾正版进服 + 保护开启 → 拒绝（防静默降级离线 UUID 导致背包丢失）
        assertEquals(Decision.GUARD_REJECT, PremiumVerificationService.decide(false, true, RECORDED));
        // 新名字 / 从未正版进服 → 维持 lenient 放行
        assertEquals(Decision.ALLOW_OFFLINE, PremiumVerificationService.decide(false, true, null));
    }

    @Test
    void guardDisabledKeepsLegacyLenientBehavior() {
        assertEquals(Decision.ALLOW_OFFLINE, PremiumVerificationService.decide(false, false, RECORDED));
        assertEquals(Decision.ALLOW_OFFLINE, PremiumVerificationService.decide(false, false, null));
    }

    @Test
    void guardMessageMentionsPlayerAndRegistryFile() {
        String message = PremiumVerificationService.guardMessage("Notch");
        assertTrue(message.contains("Notch"));
        assertTrue(message.contains(PremiumPlayerRegistry.FILE_NAME));
        assertTrue(message.contains("重新登录"));
    }
}
