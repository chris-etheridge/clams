# clams

A simple library to access [ClamAV](https://www.clamav.net/) from Clojure.

## Description

`clams` is a simple Clojure library, that provides idiomatic access, to ClamAV 
running on a server. This allows you to scan files with ClamAV, to make sure
they are not malicious.

## Usage

First, add `clams ["X.X.X"]` to your `project.clj`, where `"X.X.X"` is the current version.

Then use it as follows:

```clj
  (ns my-great-app.core
    (:require [clams.core :as clams]))
    
  (clams/scan-file file)
  
  (clams/scan-many seq-of-files)
```

More to follow. 

## License

Take a look at the `LICENSE` file in the root of this repository.
