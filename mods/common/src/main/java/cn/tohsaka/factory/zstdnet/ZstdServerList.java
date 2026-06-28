/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
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
