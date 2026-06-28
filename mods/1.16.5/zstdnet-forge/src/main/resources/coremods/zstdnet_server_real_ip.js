var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');

// 1.16.5 命名：ClientIntentionPacket -> CHandshakePacket（host 在 readPacketData 里经
// PacketBuffer.readString(I)=func_150789_c 读入私有字段 ip）；Connection -> NetworkManager；
// ServerHandshakePacketListenerImpl -> ServerHandshakeNetHandler（持有 NetworkManager 字段，
// 处理方法 processHandshake(CHandshakePacket)）。
function initializeCoreMod() {
    return {
        'zstdnet_client_intention_raw_host': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.network.handshake.client.CHandshakePacket'
            },
            'transformer': function(classNode) {
                var mappedRead = ASMAPI.mapMethod('func_150789_c');

                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.desc != '(Lnet/minecraft/network/PacketBuffer;)V') {
                        continue;
                    }

                    var patched = false;
                    for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode && insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && insn.owner == 'net/minecraft/network/PacketBuffer'
                                && (insn.name == mappedRead || insn.name == 'func_150789_c' || insn.name == 'readString')
                                && insn.desc == '(I)Ljava/lang/String;') {
                            var injected = new InsnList();
                            // stack after readString: ..., String host
                            injected.add(new InsnNode(Opcodes.DUP));
                            injected.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                                'rememberRawHandshakeHostString',
                                '(Ljava/lang/String;)Ljava/lang/String;',
                                false
                            ));
                            injected.add(new InsnNode(Opcodes.POP));
                            method.instructions.insert(insn, injected);
                            ASMAPI.log('INFO', '[zstdnet] patched CHandshakePacket#readPacketData for raw forwarded real IP host.');
                            patched = true;
                            break;
                        }
                    }

                    if (!patched) {
                        ASMAPI.log('ERROR', '[zstdnet] failed to patch CHandshakePacket - readString call not found.');
                    }
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch CHandshakePacket#readPacketData.');
                return classNode;
            }
        },
        'zstdnet_server_handshake_real_ip': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.network.handshake.ServerHandshakeNetHandler'
            },
            'transformer': function(classNode) {
                var connectionField = null;
                for (var i = 0; i < classNode.fields.size(); i++) {
                    var field = classNode.fields.get(i);
                    if (field.desc == 'Lnet/minecraft/network/NetworkManager;') {
                        connectionField = field.name;
                        break;
                    }
                }

                if (connectionField == null) {
                    ASMAPI.log('ERROR', '[zstdnet] failed to patch ServerHandshakeNetHandler - NetworkManager field not found.');
                    return classNode;
                }

                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.desc != '(Lnet/minecraft/network/handshake/client/CHandshakePacket;)V') {
                        continue;
                    }

                    var injected = new InsnList();
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    injected.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        'net/minecraft/network/handshake/ServerHandshakeNetHandler',
                        connectionField,
                        'Lnet/minecraft/network/NetworkManager;'
                    ));
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                        'applyForwardedAddress',
                        '(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/network/handshake/client/CHandshakePacket;)V',
                        false
                    ));
                    method.instructions.insert(injected);
                    ASMAPI.log('INFO', '[zstdnet] patched ServerHandshakeNetHandler for forwarded real IP.');
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch ServerHandshakeNetHandler#processHandshake.');
                return classNode;
            }
        }
    };
}
