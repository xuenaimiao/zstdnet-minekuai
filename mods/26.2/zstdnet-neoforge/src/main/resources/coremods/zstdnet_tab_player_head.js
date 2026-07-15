var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

// 把 PlayerTabOverlay#extractRenderState 里那处「连接是否安全」的头像门控中的调用替换为我方静态方法：
// 对 ZstdNet 本地代理连接也判为安全，恢复 TAB 头像。
// 26.2 该门控由旧版 Connection.isEncrypted() 改为 minecraft.getConnection().onlineMode()
//（ClientPacketListener#onlineMode），故按 owner=net/minecraft/client/multiplayer/ClientPacketListener
// + name=onlineMode + 描述符 ()Z 定位。
function initializeCoreMod() {
    return {
        'zstdnet_tab_player_head': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.client.gui.components.PlayerTabOverlay'
            },
            'transformer': function(classNode) {
                var gateOwner = 'net/minecraft/client/multiplayer/ClientPacketListener';
                var gateName = 'onlineMode';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode)) {
                            continue;
                        }
                        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                            continue;
                        }
                        if (insn.owner != gateOwner || insn.name != gateName || insn.desc != '()Z') {
                            continue;
                        }

                        var replacement = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/coremod/TabPlayerHeadHooks',
                            'isEncryptedOrZstdProxied',
                            '(Lnet/minecraft/client/multiplayer/ClientPacketListener;)Z',
                            false
                        );
                        method.instructions.set(insn, replacement);
                        ASMAPI.log('INFO', '[zstdnet] patched PlayerTabOverlay tab-head gate (show player heads on zstd proxy connections).');
                        return classNode;
                    }
                }

                ASMAPI.log('WARN', '[zstdnet] failed to patch PlayerTabOverlay tab-head gate - premium tab heads may stay hidden on zstd connections.');
                return classNode;
            }
        }
    };
}
