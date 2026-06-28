var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

// 1.16.5 没有 1.18.2 的静态 ConnectScreen.startConnecting(...)。两个 ConnectingScreen 构造器
// （(Screen,Minecraft,ServerData) 与 (Screen,Minecraft,String,int)）都汇入私有实例方法
// connect(String ip, int port)。故改注入 connect 开头：把 (ip,port) 交给钩子改写为本地代理地址，
// 钩子返回一个 ServerAddress，再用我方 hostOf/portOf 取出主机/端口写回局部变量 1(ip)/2(port)。
function initializeCoreMod() {
    return {
        'zstdnet_connect_screen_intercept': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.client.gui.screen.ConnectingScreen'
            },
            'transformer': function(classNode) {
                var exactDesc = '(Ljava/lang/String;I)V';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.desc != exactDesc) {
                        continue;
                    }
                    if (method.name == '<init>') {
                        continue;
                    }
                    if ((method.access & Opcodes.ACC_STATIC) != 0) {
                        continue;
                    }

                    var injected = new InsnList();
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 1)); // String ip
                    injected.add(new VarInsnNode(Opcodes.ILOAD, 2)); // int port
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ConnectScreenHooks',
                        'interceptConnect',
                        '(Ljava/lang/String;I)Lnet/minecraft/client/multiplayer/ServerAddress;',
                        false
                    ));
                    // stack: [addr]
                    injected.add(new InsnNode(Opcodes.DUP)); // [addr, addr]
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ConnectScreenHooks',
                        'hostOf',
                        '(Lnet/minecraft/client/multiplayer/ServerAddress;)Ljava/lang/String;',
                        false
                    ));
                    // [addr, host]
                    injected.add(new VarInsnNode(Opcodes.ASTORE, 1)); // ip = host ; [addr]
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ConnectScreenHooks',
                        'portOf',
                        '(Lnet/minecraft/client/multiplayer/ServerAddress;)I',
                        false
                    ));
                    // [port]
                    injected.add(new VarInsnNode(Opcodes.ISTORE, 2)); // port = port ; []

                    method.instructions.insert(injected);
                    ASMAPI.log('INFO', '[zstdnet] patched ConnectingScreen#connect for programmatic connection interception.');
                    return classNode;
                }

                ASMAPI.log('WARN', '[zstdnet] failed to patch ConnectingScreen#connect - ServerRedirect compatibility may not work.');
                return classNode;
            }
        }
    };
}
