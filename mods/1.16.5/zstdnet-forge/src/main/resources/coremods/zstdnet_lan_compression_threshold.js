var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

// 1.16.5 SRG 用 func_*：getNetworkCompressionThreshold = func_175577_aI ()I；DedicatedServer.init = func_71197_b ()Z。
// auto-port 段按「INVOKEVIRTUAL 返回 net/minecraft/server/dedicated/ServerProperties 且紧跟 ASTORE」定位
// init() 内 ServerPropertiesProvider.getProperties() 的取出点（描述符匹配，免依赖 SRG 方法名）。
function initializeCoreMod() {
    return {
        'zstdnet_lan_compression_threshold': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.MinecraftServer'
            },
            'transformer': function(classNode) {
                var mapped = ASMAPI.mapMethod('func_175577_aI');

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if ((method.name != mapped && method.name != 'func_175577_aI' && method.name != 'getNetworkCompressionThreshold') || method.desc != '()I') {
                        continue;
                    }

                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/LanCompressionHooks',
                        'resolveLanCompressionThreshold',
                        '(Lnet/minecraft/server/MinecraftServer;)I',
                        false
                    ));
                    method.instructions.add(new InsnNode(Opcodes.IRETURN));
                    method.maxStack = 1;
                    method.maxLocals = 1;
                    ASMAPI.log('INFO', '[zstdnet] patched MinecraftServer#getNetworkCompressionThreshold for LAN mode.');
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch MinecraftServer#getNetworkCompressionThreshold.');
                return classNode;
            }
        },
        'zstdnet_dedicated_auto_port': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.dedicated.DedicatedServer'
            },
            'transformer': function(classNode) {
                var mappedInit = ASMAPI.mapMethod('func_71197_b');

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if ((method.name != mappedInit && method.name != 'func_71197_b' && method.name != 'init') || method.desc != '()Z') {
                        continue;
                    }

                    for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                            continue;
                        }
                        if (insn.desc != '()Lnet/minecraft/server/dedicated/ServerProperties;') {
                            continue;
                        }

                        var next = insn.getNext();
                        while (next != null && next.getOpcode() == -1) {
                            next = next.getNext();
                        }
                        if (next == null || next.getOpcode() != Opcodes.ASTORE) {
                            continue;
                        }

                        var injected = new InsnList();
                        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        injected.add(new InsnNode(Opcodes.SWAP));
                        injected.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/server/DedicatedAutoPortHooks',
                            'prepareDedicatedServerProperties',
                            '(Lnet/minecraft/server/dedicated/DedicatedServer;Lnet/minecraft/server/dedicated/ServerProperties;)Lnet/minecraft/server/dedicated/ServerProperties;',
                            false
                        ));
                        method.instructions.insert(insn, injected);
                        ASMAPI.log('INFO', '[zstdnet] patched DedicatedServer#init for auto port takeover.');
                        return classNode;
                    }
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch DedicatedServer#init for auto port takeover.');
                return classNode;
            }
        }
    };
}
