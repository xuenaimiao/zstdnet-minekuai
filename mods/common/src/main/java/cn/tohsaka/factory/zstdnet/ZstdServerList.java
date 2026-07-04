/*
 * Copyright (c) 2026 wish (original author, MIT — https://github.com/wish131400/zstdnet)
 * Copyright (c) 2026 xuenai · 麦块联机 / MineKuai (https://minekuai.com)
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is a derivative work of the MIT-licensed ZstdNet by wish. wish's
 * original portions remain under the MIT License (see the LICENSE file); that
 * upstream grant is preserved and not revoked.
 *
 * This project as a whole — and all modifications and additions by xuenai — is
 * licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 4.0
 * International License (CC BY-NC-SA 4.0). You may share and adapt it for
 * NON-COMMERCIAL purposes only, must give appropriate credit and retain the
 * copyright notices above, and must distribute your contributions under this
 * same license (share-alike, source included).
 *
 * You should have received a copy of the license along with ZstdNet.
 * If not, see <https://creativecommons.org/licenses/by-nc-sa/4.0/>.
 */

package cn.tohsaka.factory.zstdnet;

import java.util.List;
import java.util.Objects;

/**
 * Client-published zstd server list model.
 */
public final class ZstdServerList {
    private final List<ZstdServer> servers;

    public ZstdServerList(List<ZstdServer> servers) {
        this.servers = servers;
    }

    public List<ZstdServer> servers() {
        return this.servers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ZstdServerList)) {
            return false;
        }
        ZstdServerList other = (ZstdServerList) o;
        return Objects.equals(this.servers, other.servers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(servers);
    }

    @Override
    public String toString() {
        return "ZstdServerList[servers=" + servers + "]";
    }

    /**
     * Simplified client entry format.
     */
    public static final class ZstdServer {
        private final String name;
        private final String addr;
        private final String mask;
        private final String mode;

        public ZstdServer(String name, String addr, String mask, String mode) {
            this.name = name;
            this.addr = addr;
            this.mask = mask;
            this.mode = mode;
        }

        public String name() {
            return this.name;
        }

        public String addr() {
            return this.addr;
        }

        public String mask() {
            return this.mask;
        }

        public String mode() {
            return this.mode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ZstdServer)) {
                return false;
            }
            ZstdServer other = (ZstdServer) o;
            return Objects.equals(this.name, other.name)
                && Objects.equals(this.addr, other.addr)
                && Objects.equals(this.mask, other.mask)
                && Objects.equals(this.mode, other.mode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, addr, mask, mode);
        }

        @Override
        public String toString() {
            return "ZstdServer[name=" + name + ", addr=" + addr + ", mask=" + mask + ", mode=" + mode + "]";
        }
    }
}
