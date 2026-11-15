# Platform selection needs just 1.56+; no additional shell installation is needed.
set minimum-version := '1.56.0'
set default-list

# source_directory() refers to this imported file, even in a component justfile.
export EXOTICA_ROOT := parent_directory(source_directory())

import 'unix.just'
import 'windows.just'
