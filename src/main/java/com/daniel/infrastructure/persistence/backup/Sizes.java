package com.daniel.infrastructure.persistence.backup;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Byte counts as the screen shows them (binary units, pt-BR decimal comma: "12,3 MB"). */
public final class Sizes {

    private Sizes() {
    }

    public static String text(long bytes) {
        if (bytes < 0) return "indisponível";
        if (bytes < 1024) return bytes + (bytes == 1 ? " byte" : " bytes");
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.scale() < 0) rounded = rounded.setScale(0);
        return rounded.toPlainString().replace('.', ',') + " " + units[unit];
    }
}
