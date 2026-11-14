package fr.mas.email.world;

import java.io.IOException;
import java.util.Optional;

/** A capability bound to ONE world; callers cannot supply a different owner's ID or path. */
public interface WorldRepository<T> {
    Optional<T> load() throws IOException;
    void save(T world) throws IOException;
}
