var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

// 把 PlayerTabOverlayGui#render 里那处「连接是否加密」的门控（isIntegratedServerRunning() || connection.isEncrypted()）
// 中的 NetworkManager.isEncrypted() 调用替换为我方静态方法：对 ZstdNet 本地代理连接也判为安全，恢复 TAB 头像。
// 不依赖被混淆的方法名——按「owner=net/minecraft/network/NetworkManager 且描述符 ()Z」唯一定位 isEncrypted 调用
//（render 内对 NetworkManager 的唯一布尔调用就是 isEncrypted；isIntegratedServerRunning 的 owner 是 Minecraft）。
function initializeCoreMod() {
    return {
        'zstdnet_tab_player_head': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.client.gui.overlay.PlayerTabOverlayGui'
            },
            'transformer': function(classNode) {
                var connOwner = 'net/minecraft/network/NetworkManager';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode)) {
                            continue;
                        }
                        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                            continue;
                        }
                        if (insn.owner != connOwner || insn.desc != '()Z') {
                            continue;
                        }

                        var replacement = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/coremod/TabPlayerHeadHooks',
                            'isEncryptedOrZstdProxied',
                            '(Lnet/minecraft/network/NetworkManager;)Z',
                            false
                        );
                        method.instructions.set(insn, replacement);
                        ASMAPI.log('INFO', '[zstdnet] patched PlayerTabOverlayGui tab-head gate (show player heads on zstd proxy connections).');
                        return classNode;
                    }
                }

                ASMAPI.log('WARN', '[zstdnet] failed to patch PlayerTabOverlayGui tab-head gate - premium tab heads may stay hidden on zstd connections.');
                return classNode;
            }
        }
    };
}
