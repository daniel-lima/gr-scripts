/*
 * Copyright 2010-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author Daniel Henrique Alves Lima
 */
includeTargets << grailsScript("_GrailsDocs")

def docBranch = "doc"
def docChildren = [~/manual/]
//def docChildren = [~/.*/]
def svnAutoProps = [
    '.html': ['svn:mime-type': 'text/html']
]


USAGE = """
    publish-googlecode [--commit] [--push]

where
    --commit  = Commits changes in the documentation to the '${docBranch}' branch.
                You are left on the working copy of '${docBranch}' branch so you can update the
                other files before pushing.
    --push    = Pushes the changes to GoogleCode and switches back to the original
                working copy. Implies --commit.
"""

target(default: "Generates the plugin documentation and makes it available on your doc branch.") {
    depends(parseArguments)
    
    def output = executeSvn("info")
    def info = [:]
    
    def matcher = output =~ /Repository Root:\s+(.*)/
    if (matcher) {
        info.repositoryRoot = matcher[0][1]
        println "Repository Root: ${info.repositoryRoot}"
    } else {
        println "Unable to find out the Repository Root. Output from 'svn info' was: ${output}"
        exit 1
    }
    
    
    // We have to generate the docs on the current branch because the
    // 'docs' target depends on 'compile', which of course requires all
    // the source files.
    def docsDir
    try {
        docsDir = grailsSettings.docsOutputDir
    }
    catch (MissingPropertyException ex) {
        docsDir = new File("${basedir}/docs")
    }
    ant.delete(dir: docsDir.absolutePath)
    docs()
    
    info.docURL = "${info.repositoryRoot}/${docBranch}"
    
    def tmpDocDir = File.createTempFile('docs', 'tmp')
    tmpDocDir.delete()
    
    println "tmpDocDir ${tmpDocDir}"
    println "docURL ${info.docURL}"
    
    out = new StringBuilder()
    def exitValue = executeSvn("checkout ${info.docURL} ${tmpDocDir}", out)
    //echo "${exitValue} ${exitValue.class}"
    if (exitValue > 0) {
        println "To create ${docBranch} branch, type 'svn mkdir ${info.docURL}'"
        exit exitValue
    }
    
    def subDirs = new LinkedHashSet()
    def newFilePaths = new LinkedHashSet()
    def oldFilePaths = new LinkedHashSet()
    
    def listFilesRecursively = {
        File dir, FileFilter fileFilter ->
            def files = []
            for (file in dir.listFiles(fileFilter)) {
                files << file
                if (file.isDirectory()) {
                    files.addAll(call(file as File, fileFilter as FileFilter))
                }
            }
            
            return files
    }
    
    def fileFilter = {
        file-> return !file.isDirectory() || !file.name.equals(".svn")
    } as FileFilter
    
    
    def getRelativePath = {
        File baseDir, File file ->
            def p1 = baseDir.absolutePath
            def p2 = file.absolutePath
            
            if (p2.startsWith(p1)) {
                return p2.substring(p1.length() + 1).replace('\\' as char, '/' as char)
            } else {
                return null
            }
    }
    
    /* Build difference sets. */
    docsDir.eachFile {
        child ->
            docChildren.each {
                pattern ->
                    if (pattern.matcher(child.name).matches()) {
                        println "Match found for ${child}"
                        
                        newFilePaths << getRelativePath(docsDir, child)
                        def oldChild = new File(child.name, tmpDocDir)
                        if (oldChild.exists()) {
                            oldFilePaths << getRelativePath(tmpDocDir, oldChild)
                        }
                        
                        if (child.isDirectory()) {
                            subDirs << child
                            listFilesRecursively(child, fileFilter).each {
                                newFilePaths << getRelativePath(docsDir, it)
                            }
                        }
                        if (oldChild.isDirectory()) {
                            listFilesRecursively(oldChild, fileFilter).each {
                                oldFilePaths << getRelativePath(tmpDocDir, it)
                            }
                        }
                    }
            }
    }
    
    def removeAllPreservingOrder = {
        LinkedHashSet a, LinkedHashSet b ->
            def c = new LinkedHashSet()
            
            a.each {
                if (!b.contains(it)) {
                    c.add(it)
                }
            }
            
            return c
    }
    
    def removedFilePaths = removeAllPreservingOrder(oldFilePaths, newFilePaths)
    def addedFilePaths = removeAllPreservingOrder(newFilePaths, oldFilePaths)
    
    //println "removed files ${removedFilePaths}"
    //println "added files ${addedFilePaths}"
    
    /* Copy the modified files to the new location. */
    subDirs.each {
        subDir ->
            ant.copy(todir: tmpDocDir, overwrite: true) {
                fileset(dir: docsDir.absolutePath) {
                    def subDirName = getRelativePath(docsDir, subDir)
                    include name: subDir.isDirectory()? "${subDirName}/**" : subDirName
                }
            }
    }
    
    /* "Persist" changes to SVN. */
    println "Removing files from ${tmpDocDir.absolutePath}"
    removedFilePaths.each {
        removedPath ->
            def out = executeSvn([dir:tmpDocDir.absolutePath, cmd: ["remove", removedPath]])
            print out
    }

    println "Adding files to ${tmpDocDir.absolutePath}"
    addedFilePaths.each {
        addedPath ->
            def out = executeSvn([dir:tmpDocDir.absolutePath, cmd: ["-N", "add", addedPath]])
            print out
            def file = new File(addedPath, tmpDocDir)
            if (file.isFile() && svnAutoProps) {
                def ext = file.name.tokenize('.')
                if (ext && ext.size() > 1) {
                    ext = '.' + ext[ext.size()-1].toLowerCase()
                } else {
                    ext = null
                }
                
                //println "ext: ${ext} - ${file}"
                
                if (ext && svnAutoProps[ext]) {
                    svnAutoProps[ext].entrySet().each {
                        entry ->
                            executeSvn([dir: tmpDocDir.absolutePath, cmd: ["propset", entry.key, entry.value, addedPath]])
                    }
                }
            }
    }  
    
    //println "argsMap ${argsMap}"
    
    // If the user wants to, also commit and push the changes.
    if (argsMap["commit"] || argsMap["push"]) {
        def out = executeSvn([dir:tmpDocDir.absolutePath, cmd: ["commit", "-m", "Auto-publication of plugin docs."]])
        println out
    }
    
}


def executeSvn(args, Appendable pOut = null) {
    def debugCmd = false
    def baseDir = null
    def cmd = args
    
    if (args && args instanceof Map) {
        baseDir = args.dir
        cmd = args.cmd
    }

    //def cmdArgs = cmd
    cmd = cmd instanceof List ? ["svn"] + cmd : "svn " + cmd

    if (debugCmd) {
        println "[${baseDir?baseDir:'.'}] ${cmd}"
    }
  

    def process = baseDir? cmd.execute(null, new File(baseDir)): cmd.execute()
    def error = new StringBuilder()
    def out = pOut? pOut: new StringBuilder()
    //process.waitForProcessOutput(out, error) Groovy 1.7.5+
    process.waitFor()
    error.append(process.err.text)
    out.append(process.in.text)
  
    def exitValue = process.exitValue()
    if (exitValue != 0) {
        println error.toString()
        if (!pOut) {
            exit(exitValue)
        }
    }
  
  
    if (pOut != null) {
        return exitValue
    } else {
        return out.toString()
    }
}
