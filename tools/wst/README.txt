The complete WST package is not included here, only the JARs we depend on (and their dependencies.)  This is to prevent accidental dependency bloat (e.g. we don't do a quick-fix to depend on some plugin without realizing its ramifications) and keep unused files out of perforce.

The list of JARs was generated by:
(1) Extracting Eclipse twice:  EclA and EclB
(2) Installing WST in EclA via e.g. Galileo update site
(3) Creating a launch configuration in EclA
  (a) Clear all plugins
  (b) Check the direct dependencies (e.g. ...css.core and ...css.ui)
  (c) Click the required plugins button
(4) Launch the configuration
(5) Look at the generated files in the configuration dir, find the file that lists the plugins that would be included in this launch configuration
(6) See which of those plugins is missing from EclB

