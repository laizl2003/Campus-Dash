package com.campusdash.shared;

/**
 * 金额值对象：内部一律用 long 存"分"，永不使用 double/float。
 * 资金系统里浮点误差是不可接受的，整数运算既精确又快。
 */
public record Money(long cents) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("金额不能为负: " + cents);
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    /** 仅用于展示层与测试构造，内部计算永远走 cents */
    public static Money ofYuan(String yuan) {
        String[] parts = yuan.split("\\.");
        long integerPart = Long.parseLong(parts[0]) * 100;
        if (parts.length == 1) {
            return new Money(integerPart);
        }
        String decimal = (parts[1] + "00").substring(0, 2);
        return new Money(integerPart + Long.parseLong(decimal));
    }

    public Money plus(Money other) {
        return new Money(this.cents + other.cents);
    }

    public Money minus(Money other) {
        return new Money(this.cents - other.cents);
    }

    public boolean isZero() {
        return cents == 0;
    }

    public boolean greaterThan(Money other) {
        return this.cents > other.cents;
    }

    @Override
    public int compareTo(Money o) {
        return Long.compare(this.cents, o.cents);
    }

    @Override
    public String toString() {
        return (cents / 100) + "." + String.format("%02d", cents % 100);
    }
}
