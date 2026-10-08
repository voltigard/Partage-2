package com.devai.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectStore(private val context: Context) {
    private val root: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    fun listProjects(): List<String> = root.listFiles()?.filter(File::isDirectory)?.map(File::getName)?.sorted() ?: emptyList()
    fun createProject(name: String): Boolean { val safe=sanitize(name); return safe.isNotBlank() && File(root,safe).mkdirs() }
    fun projectDir(name: String): File = File(root, sanitize(name))
    fun listEntries(project: String): List<String> { val base=projectDir(project); if(!base.exists()) return emptyList(); return base.walkTopDown().filter{it!=base}.map{it.relativeTo(base).invariantSeparatorsPath + if(it.isDirectory) "/" else ""}.sorted().toList() }
    fun createFile(project: String, path: String): Boolean { val f=safeFile(project,path) ?: return false; f.parentFile?.mkdirs(); return !f.exists() && f.createNewFile() }
    fun readFile(project: String, path: String): String { val f=safeFile(project,path) ?: return ""; return if(f.isFile) f.readText() else "" }
    fun writeFile(project: String, path: String, content: String): Boolean { val f=safeFile(project,path) ?: return false; f.parentFile?.mkdirs(); f.writeText(content); return true }
    fun delete(project: String, path: String): Boolean { val f=safeFile(project,path) ?: return false; return f.exists() && f.deleteRecursively() }
    fun rename(project: String, path: String, newName: String): Boolean {
        val source = safeFile(project, path) ?: return false
        if (!source.exists()) return false
        val cleanName = sanitize(newName)
        if (cleanName.isBlank()) return false
        val parent = source.parentFile ?: return false
        val root = projectDir(project).canonicalFile
        val canonicalParent = parent.canonicalFile
        if (canonicalParent.path != root.path && !canonicalParent.path.startsWith(root.path + File.separator)) return false
        val target = File(parent, cleanName)
        if (target.exists()) return false
        return runCatching { source.renameTo(target) }.getOrDefault(false)
    }
    fun paste(project: String, sourcePath: String, destinationPath: String): Boolean { val s=safeFile(project,sourcePath) ?: return false; val d=safeFile(project,destinationPath) ?: return false; if(!s.exists()||d.exists()) return false; if(s.isDirectory) copyDir(s,d) else {d.parentFile?.mkdirs();s.copyTo(d)}; return true }
    fun importFile(project: String, destinationPath: String, uri: Uri): Boolean = runCatching { val d=safeFile(project,destinationPath) ?: return false; d.parentFile?.mkdirs(); context.contentResolver.openInputStream(uri)?.use{input->FileOutputStream(d).use{input.copyTo(it)}} != null }.getOrDefault(false)
    fun importZip(project: String, destinationPath: String, uri: Uri): Boolean = runCatching { val base=safeFile(project,destinationPath) ?: return false; base.mkdirs(); context.contentResolver.openInputStream(uri)!!.use{input->ZipInputStream(input).use{z-> var e=z.nextEntry; while(e!=null){ val target=File(base,e.name).canonicalFile; val rootPath=base.canonicalPath; if(target.path!=rootPath&&!target.path.startsWith(rootPath+File.separator)) return false; if(e.isDirectory) target.mkdirs() else {target.parentFile?.mkdirs();FileOutputStream(target).use{z.copyTo(it)}}; z.closeEntry();e=z.nextEntry }}}; true }.getOrDefault(false)
    fun importDirectory(project: String, destinationPath: String, uri: Uri): Boolean = runCatching { val dest=safeFile(project,destinationPath) ?: return false; dest.mkdirs(); copyTree(uri,dest) }.getOrDefault(false)
    fun exportZip(project: String, path: String?, output: Uri): Boolean = runCatching { val source=if(path.isNullOrBlank())projectDir(project) else safeFile(project,path) ?: return false; context.contentResolver.openOutputStream(output)!!.use{out->ZipOutputStream(out).use{z->addZip(source,source.name,z)}}; true }.getOrDefault(false)
    private fun copyTree(tree: Uri, dest: File): Boolean { val id=DocumentsContract.getTreeDocumentId(tree); copyChildren(tree,id,dest); return true }
    private fun copyChildren(tree: Uri,parentId:String,dest:File){ val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId); val p=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE); context.contentResolver.query(uri,p,null,null,null)?.use{c->val id=c.getColumnIndexOrThrow(p[0]);val name=c.getColumnIndexOrThrow(p[1]);val mime=c.getColumnIndexOrThrow(p[2]);while(c.moveToNext()){val childId=c.getString(id);val n=sanitize(c.getString(name));val target=File(dest,n);val doc=DocumentsContract.buildDocumentUriUsingTree(tree,childId);if(c.getString(mime)==DocumentsContract.Document.MIME_TYPE_DIR){target.mkdirs();copyChildren(tree,childId,target)}else{target.parentFile?.mkdirs();context.contentResolver.openInputStream(doc)?.use{input->FileOutputStream(target).use{input.copyTo(it)}}}}}}
    private fun addZip(f:File,path:String,z:ZipOutputStream){if(f.isDirectory){val children=f.listFiles()?:emptyArray();if(children.isEmpty()){z.putNextEntry(ZipEntry("$path/"));z.closeEntry()};children.forEach{addZip(it,"$path/${it.name}",z)}}else{z.putNextEntry(ZipEntry(path));f.inputStream().use{it.copyTo(z)};z.closeEntry()}}
    private fun copyDir(s:File,d:File){d.mkdirs();s.listFiles()?.forEach{c->val x=File(d,c.name);if(c.isDirectory)copyDir(c,x)else{ x.parentFile?.mkdirs();c.copyTo(x)}}}
    private fun safeFile(project:String,path:String):File?{val base=projectDir(project).canonicalFile;val f=File(base,path).canonicalFile;return if(f.path==base.path||f.path.startsWith(base.path+File.separator))f else null}
    private fun sanitize(v:String)=v.trim().replace(Regex("[^A-Za-z0-9._-]+"),"_")
}
