package com.daniel.infrastructure.persistence.backup;

/**
 * Tells whether this is the only running BreakInv on the data folder. A restore replaces the data
 * under every open window, so it proceeds only while exclusive.
 */
public interface InstanceGuard {

    /** True when no other instance holds the data folder; the caller then owns it until released. */
    boolean tryAcquireExclusive();

    /** Gives exclusivity back (other instances may open again). */
    void releaseExclusive();

    /** pt-BR reason shown when {@link #tryAcquireExclusive()} is false. */
    default String refusal() {
        return "outra janela do BreakInv está aberta. Feche-a e tente de novo; nada foi alterado.";
    }

    /** For tests and tools that run without other instances. */
    InstanceGuard SOLE = new InstanceGuard() {
        @Override
        public boolean tryAcquireExclusive() {
            return true;
        }

        @Override
        public void releaseExclusive() {
        }
    };

    /** When the instance lock could not be taken: a restore is refused rather than risked. */
    InstanceGuard UNVERIFIABLE = new InstanceGuard() {
        @Override
        public boolean tryAcquireExclusive() {
            return false;
        }

        @Override
        public void releaseExclusive() {
        }

        @Override
        public String refusal() {
            return "não foi possível confirmar que esta é a única janela do BreakInv aberta (o arquivo "
                    + FileInstanceLock.FILE_NAME + ", ao lado dos dados, não pôde ser usado). Feche o BreakInv, "
                    + "abra de novo e tente outra vez; nada foi alterado.";
        }
    };
}
