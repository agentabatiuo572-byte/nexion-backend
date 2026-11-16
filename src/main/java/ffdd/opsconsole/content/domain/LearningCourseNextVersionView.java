package ffdd.opsconsole.content.domain;

/** Admin read-only hint for the next free vN label; the client still submits its chosen version. */
public record LearningCourseNextVersionView(String nextVersion) {}
