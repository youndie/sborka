package stand.ksp

/**
 * What the stand's processor looks for. Two annotated types below, so the generated file has
 * content a test can be wrong about.
 */
@Target(AnnotationTarget.CLASS)
public annotation class Marked

@Marked
public class Alpha

@Marked
public class Beta
