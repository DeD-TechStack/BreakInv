package com.daniel.infrastructure.persistence.backup;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Deterministic {@link SpaceProbe}: fixed answers per path, so no test ever fills a real disk. */
final class FakeSpaceProbe implements SpaceProbe {

    private final Map<Path, Space> answers = new HashMap<>();
    private final Space otherwise;

    private FakeSpaceProbe(Space otherwise) {
        this.otherwise = otherwise;
    }

    /** Every path not configured with {@link #on} reports the same volume with {@code bytes} free. */
    static FakeSpaceProbe all(long bytes) {
        return new FakeSpaceProbe(new Measured(bytes, null, "VOL"));
    }

    /** Every path not configured with {@link #on} is unavailable. */
    static FakeSpaceProbe none() {
        return new FakeSpaceProbe(new Unavailable("não configurado no teste"));
    }

    FakeSpaceProbe on(Path path, Space answer) {
        answers.put(path.toAbsolutePath().normalize(), answer);
        return this;
    }

    @Override
    public Space usableSpace(Path path) {
        if (path == null) return new Unavailable("caminho não informado");
        Path key = path.toAbsolutePath().normalize();
        Space s = answers.get(key);
        if (s != null) return s;
        if (otherwise instanceof Measured m) return new Measured(m.usableBytes(), key, m.volume());
        return otherwise;
    }
}
