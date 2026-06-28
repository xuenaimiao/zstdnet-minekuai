package cn.tohsaka.factory.zstdnet.core.protocol;

import java.util.Objects;

public final class VarIntRead {
    private final int value;
    private final int next;

    public VarIntRead(int value, int next) {
        this.value = value;
        this.next = next;
    }

    public int value() {
        return this.value;
    }

    public int next() {
        return this.next;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VarIntRead)) {
            return false;
        }
        VarIntRead other = (VarIntRead) o;
        return this.value == other.value && this.next == other.next;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.value, this.next);
    }

    @Override
    public String toString() {
        return "VarIntRead[value=" + this.value + ", next=" + this.next + "]";
    }
}
