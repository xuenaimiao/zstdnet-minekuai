var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');

var SERVER_HOOKS = 'cn/tohsaka/factory/zstdnet/coremod/PremiumAuthServerHooks';
var CLIENT_HOOKS = 'cn/tohsaka/factory/zstdnet/coremod/PremiumAuthClientHooks';
var SERVER_LISTENER = 'net/minecraft/server/network/ServerLoginPacketListenerImpl';
var CLIENT_LISTENER = 'net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl';
var SB_QUERY = 'net/minecraft/network/protocol/login/ServerboundCustomQueryPacket';
var CB_QUERY = 'net/minecraft/network/protocol/login/ClientboundCustomQueryPacket';
var GAME_PROFILE_PACKET = 'net/minecraft/network/protocol/login/ClientboundGameProfilePacket';
var CONNECTION = 'net/minecraft/network/Connection';

// 「登录阶段正版验证」coremod（旧版登录流程：Forge 1.18.2 / 1.19.2 / 1.20.1，NeoForge 1.20.1 复用）。
// 注：Forge 运行时仅混淆方法/字段名，类名保持可读，故按「方法构造 ClientboundGameProfilePacket」
// 与「参数类型」定位目标方法，对 SRG/官方映射两种运行时都稳健。
function initializeCoreMod() {
    return {
        'zstdnet_premium_auth_server': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.server.network.ServerLoginPacketListenerImpl' },
            'transformer': function (classNode) {
                // (1) 门控 handleAcceptedLogin()V —— 唯一构造 ClientboundGameProfilePacket 的 ()V 方法
                var gate = null;
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var m = classNode.methods.get(i);
                    if (m.desc != '()V') {
                        continue;
                    }
                    for (var k = 0; k < m.instructions.size(); k++) {
                        var insn = m.instructions.get(k);
                        if (insn.getOpcode && insn.getOpcode() == Opcodes.NEW && insn.desc == GAME_PROFILE_PACKET) {
                            gate = m;
                            break;
                        }
                    }
                    if (gate != null) {
                        break;
                    }
                }
                if (gate == null) {
                    ASMAPI.log('ERROR', '[zstdnet] premium-auth: handleAcceptedLogin not found.');
                } else {
                    var g = new InsnList();
                    var gCont = new LabelNode();
                    g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SERVER_HOOKS, 'beforeFinalizeLogin',
                        '(L' + SERVER_LISTENER + ';)Z', false));
                    g.add(new JumpInsnNode(Opcodes.IFNE, gCont)); // true=放行 → 跳过 RETURN
                    g.add(new InsnNode(Opcodes.RETURN));
                    g.add(gCont);
                    gate.instructions.insert(g);
                    ASMAPI.log('INFO', '[zstdnet] premium-auth: gated handleAcceptedLogin.');
                }

                // (2) 拦截 handleCustomQueryPacket(ServerboundCustomQueryPacket)V
                var ansDesc = '(L' + SB_QUERY + ';)V';
                var hooked = false;
                for (var j = 0; j < classNode.methods.size(); j++) {
                    var mm = classNode.methods.get(j);
                    if (mm.desc != ansDesc) {
                        continue;
                    }
                    var a = new InsnList();
                    var aCont = new LabelNode();
                    a.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    a.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    a.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SERVER_HOOKS, 'handleAnswer',
                        '(L' + SERVER_LISTENER + ';L' + SB_QUERY + ';)Z', false));
                    a.add(new JumpInsnNode(Opcodes.IFEQ, aCont)); // false=非我方 → 交还原版
                    a.add(new InsnNode(Opcodes.RETURN));
                    a.add(aCont);
                    mm.instructions.insert(a);
                    hooked = true;
                    ASMAPI.log('INFO', '[zstdnet] premium-auth: hooked server handleCustomQueryPacket.');
                    break;
                }
                if (!hooked) {
                    ASMAPI.log('ERROR', '[zstdnet] premium-auth: server handleCustomQueryPacket not found.');
                }
                return classNode;
            }
        },
        'zstdnet_premium_auth_client': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl' },
            'transformer': function (classNode) {
                var connField = null;
                for (var i = 0; i < classNode.fields.size(); i++) {
                    var f = classNode.fields.get(i);
                    if (f.desc == 'L' + CONNECTION + ';') {
                        connField = f.name;
                        break;
                    }
                }
                if (connField == null) {
                    ASMAPI.log('ERROR', '[zstdnet] premium-auth: client Connection field not found.');
                    return classNode;
                }
                var qDesc = '(L' + CB_QUERY + ';)V';
                var done = false;
                for (var j = 0; j < classNode.methods.size(); j++) {
                    var m = classNode.methods.get(j);
                    if (m.desc != qDesc) {
                        continue;
                    }
                    var c = new InsnList();
                    var cCont = new LabelNode();
                    c.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    c.add(new FieldInsnNode(Opcodes.GETFIELD, CLIENT_LISTENER, connField, 'L' + CONNECTION + ';'));
                    c.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLIENT_HOOKS, 'handleClientQuery',
                        '(L' + CONNECTION + ';L' + CB_QUERY + ';)Z', false));
                    c.add(new JumpInsnNode(Opcodes.IFEQ, cCont)); // false=非我方 → 交还原版
                    c.add(new InsnNode(Opcodes.RETURN));
                    c.add(cCont);
                    m.instructions.insert(c);
                    done = true;
                    ASMAPI.log('INFO', '[zstdnet] premium-auth: hooked client handleCustomQuery.');
                    break;
                }
                if (!done) {
                    ASMAPI.log('ERROR', '[zstdnet] premium-auth: client handleCustomQuery not found.');
                }
                return classNode;
            }
        }
    };
}
