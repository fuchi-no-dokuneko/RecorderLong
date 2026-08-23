# Recording session recovery schema

Existing releases kept active segment state only in memory. The target schema is a versioned JSON journal in private app preferences containing the session name, display path, active state, and completed segment references (`content://` URI or legacy file path). The service commits the journal before recording and after every completed segment.

On upgrade there is no old persistent schema to migrate. Existing media files remain untouched. An interrupted version-1 journal can be recovered into hour files or dismissed while keeping its parts. A malformed or unknown journal is preserved and surfaced; it is never silently replaced.
