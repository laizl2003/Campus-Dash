package com.campusdash.domain;

import com.campusdash.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    @DisplayName("金额用整数分表示，不存在浮点误差")
    void noFloatingPointError() {
        Money a = Money.ofYuan("0.1");
        Money b = Money.ofYuan("0.2");
        assertEquals(30L, a.plus(b).cents());
        assertEquals("0.30", a.plus(b).toString());
    }

    @Test
    @DisplayName("负数金额直接拒绝构造")
    void negativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofCents(-1));
        assertThrows(IllegalArgumentException.class, () -> Money.ofCents(100).minus(Money.ofCents(101)));
    }

    @Test
    @DisplayName("元转分覆盖一位小数与无小数")
    void yuanParsing() {
        assertEquals(1500L, Money.ofYuan("15").cents());
        assertEquals(1550L, Money.ofYuan("15.5").cents());
        assertEquals(1555L, Money.ofYuan("15.55").cents());
    }
}
