package com.daniel.infrastructure.persistence.backup;

/**
 * The reason shown for a failed write of the data (BRK-31 u4b, U4B:RF-02), by the category of the failure
 * ({@link SqliteErrors#ofChain}). Provisional wording until BRK-31 u5, like the reasons of the copy
 * ({@code BackupService.describe}), which are other texts. It carries no file path: the data file is a single one.
 */
public final class WriteFailureReason {

    private WriteFailureReason() {
    }

    /**
     * The reason in simple words, or {@code todayMessage} (what the screen showed before this unit) when the
     * category is OTHER.
     */
    public static String of(Throwable failure, String todayMessage) {
        return switch (SqliteErrors.ofChain(failure)) {
            case SPACE -> "disco cheio";
            case IO -> "erro de leitura ou gravação no disco";
            case CANT_OPEN -> "arquivo de dados inacessível";
            case WRITE_DENIED -> "sem permissão de gravação no arquivo de dados";
            case BUSY -> "dados em uso por outra operação";
            case DAMAGED -> "arquivo de dados danificado";
            case OTHER -> todayMessage;
        };
    }
}
