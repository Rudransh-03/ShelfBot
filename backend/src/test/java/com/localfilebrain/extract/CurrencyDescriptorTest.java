package com.localfilebrain.extract;

import org.junit.jupiter.api.Test;

import static com.localfilebrain.extract.CurrencyDescriptor.Grouping.INDIAN;
import static com.localfilebrain.extract.CurrencyDescriptor.Grouping.WESTERN;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrencyDescriptorTest {

    @Test
    void westernGrouping() {
        assertEquals("2,066,600", CurrencyDescriptor.westernGroup(2066600));
        assertEquals("1,500", CurrencyDescriptor.westernGroup(1500));
        assertEquals("500", CurrencyDescriptor.westernGroup(500));
    }

    @Test
    void indianGrouping() {
        assertEquals("20,66,600", CurrencyDescriptor.indianGroup(2066600));
        assertEquals("1,500", CurrencyDescriptor.indianGroup(1500));
        assertEquals("500", CurrencyDescriptor.indianGroup(500));
        assertEquals("-20,66,600", CurrencyDescriptor.indianGroup(-2066600));
    }

    @Test
    void symbolAttachesDirectly_forGlyphs() {
        assertEquals("₹15,00,000", new CurrencyDescriptor("₹", INDIAN).format(1500000));
        assertEquals("$1,500,000", new CurrencyDescriptor("$", WESTERN).format(1500000));
    }

    @Test
    void alphabeticCodeGetsSpace() {
        assertEquals("USD 1,500", new CurrencyDescriptor("USD", WESTERN).format(1500));
        assertEquals("Rs. 15,000", new CurrencyDescriptor("Rs.", INDIAN).format(15000));
    }

    @Test
    void neutralDefault_isNotRupee_andRendersBareNumber() {
        // The critical safety property: NONE never silently prints ₹.
        assertEquals("", CurrencyDescriptor.NONE.symbol());
        assertEquals("1,500", CurrencyDescriptor.NONE.format(1500));
    }
}
