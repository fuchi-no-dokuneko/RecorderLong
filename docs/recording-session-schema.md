# Recording session recovery schema

Existing releases kept active segment state only in memory. The target schema is version 2 JSON in private app preferences containing the session name, display path, active state, and segment references (`content://` URI or legacy file path) marked partial or complete. The service commits the current partial reference before recording and commits again after every completed segment.

On upgrade there is no released persistent schema to migrate. Existing media files remain untouched. A version-2 journal can be copied from app-private backup and inspected offline as JSON. An interrupted journal can be recovered into hour files or dismissed while keeping its parts. A malformed or unknown journal is preserved and surfaced; it is never silently replaced.
