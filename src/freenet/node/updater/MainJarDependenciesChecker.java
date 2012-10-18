/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.updater;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import freenet.client.FetchException;
import freenet.crypt.SHA256;
import freenet.keys.FreenetURI;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.EOFInputStream;

/**
 * Parses the dependencies.properties file and ensures we have all the 
 * libraries required to use the next version. Calls the Deployer to do the
 * actual fetches, and to deploy the new version when we have everything 
 * ready.
 * 
 * Each dependency has a single required version for a particular build.
 * We don't deploy the build until we have fetched that version. Each
 * version has a unique filename. Note that the 
 * 
 * We used to support a range of freenet-ext.jar versions. However, 
 * supporting ranges creates a lot of complexity, especially with Update 
 * Over Mandatory support.
 * @author toad
 *
 */
public class MainJarDependenciesChecker {
	
	// Slightly over-engineered? No.
	// This is critical code. It is essential that we are able to unit test it.
	// Hence the lightweight interfaces, with the mundane glue code implemented by the caller.
	
	class MainJarDependencies {
		/** The freenet.jar build to be deployed. It might be possible to
		 * deploy a new build without changing the wrapper. */
		final int build;
		/** The actual dependencies. */
		final Set<Dependency> dependencies;
		/** True if we must rewrite wrapper.conf, i.e. if any new jars have
		 * been added, or new versions of existing jars. Won't be reliably
		 * true in case of jars being removed at present. FIXME see comments
		 * in handle() about deletion placeholders! */
		final boolean mustRewriteWrapperConf;
		
		MainJarDependencies(Set<Dependency> dependencies, int build) {
			this.dependencies = dependencies;
			this.build = build;
			boolean mustRewrite = false;
			for(Dependency d : dependencies) {
				if(d.oldFilename == null || !d.oldFilename.equals(d.newFilename)) {
					mustRewrite = true;
					break;
				}
				if(File.pathSeparatorChar == ':' &&
						d.oldFilename != null && d.oldFilename.getName().equalsIgnoreCase("freenet-ext.jar.new")) {
					// If wrapper.conf currently contains freenet-ext.jar.new, we need to update wrapper.conf even
					// on unix. Reason: freenet-ext.jar.new won't be read if it's not the first item on the classpath,
					// because freenet.jar includes freenet-ext.jar implicitly via its manifest.
					mustRewrite = true;
					break;
				}
			}
			mustRewriteWrapperConf = mustRewrite;
		}
	}
	
	interface Deployer {
		public void deploy(MainJarDependencies deps);
		public JarFetcher fetch(FreenetURI uri, File downloadTo, long expectedLength, byte[] expectedHash, JarFetcherCallback cb, int build) throws FetchException;
		/** Called by cleanup with the dependencies we can serve for the current version. 
		 * @param expectedHash The hash of the file's contents, which is also
		 * listed in the dependencies file.
		 * @param filename The local file to serve it from. */
		public void addDependency(byte[] expectedHash, File filename);
	}
	
	interface JarFetcher {
		public void cancel();
	}
	
	interface JarFetcherCallback {
		public void onSuccess();
		public void onFailure(FetchException e);
	}

	final class Dependency {
		private File oldFilename;
		private File newFilename;
		/** For last resort matching */
		private Pattern regex;
		
		private Dependency(File oldFilename, File newFilename, Pattern regex) {
			this.oldFilename = oldFilename;
			this.newFilename = newFilename;
			this.regex = regex;
		}
		
		public File oldFilename() {
			return oldFilename;
		}
		
		public File newFilename() {
			return newFilename;
		}
		
		public Pattern regex() {
			return regex;
		}
	}
	
	MainJarDependenciesChecker(Deployer deployer) {
		this.deployer = deployer;
	}

	private final Deployer deployer;
	/** The final filenames we will use in the update, which we have 
	 * already downloaded. */
	private final HashSet<Dependency> dependencies = new HashSet<Dependency>();
	/** Set if the update can't be deployed because the dependencies file is 
	 * broken. We should wait for an update with a valid file. 
	 */
	private boolean broken = false;
	/** Set when we are ready to deploy. We won't look at new jars after that. */
	private boolean deploying = false;
	/** The build we are about to deploy */
	private int build;
	
	private class Downloader implements JarFetcherCallback {
		
		final JarFetcher fetcher;
		final Dependency dep;
		final boolean essential;

		/** Construct with a Dependency, so we can add it when we're done. */
		Downloader(Dependency dep, FreenetURI uri, byte[] expectedHash, long expectedSize) throws FetchException {
			fetcher = deployer.fetch(uri, dep.newFilename, expectedSize, expectedHash, this, build);
			this.dep = dep;
			this.essential = true;
		}

		@Override
		public void onSuccess() {
			if(!essential) {
				System.out.println("Downloaded "+dep.newFilename+" - may be used by next update");
				return;
			}
			System.out.println("Downloaded "+dep.newFilename+" needed for update...");
			synchronized(MainJarDependenciesChecker.this) {
				downloaders.remove(this);
				dependencies.add(dep);
				if(!ready()) return;
			}
			deploy();
		}

		@Override
		public void onFailure(FetchException e) {
			if(!essential) {
				Logger.error(this, "Failed to pre-load "+dep.newFilename+" : "+e, e);
			} else {
				System.err.println("Failed to fetch "+dep.newFilename+" needed for next update ("+e.getShortMessage()+"). Will try again if we find a new freenet.jar.");
				synchronized(MainJarDependenciesChecker.this) {
					downloaders.remove(this);
					broken = false;
				}
			}
		}
		
		public void cancel() {
			fetcher.cancel();
		}
		
	}
	
	private final HashSet<Downloader> downloaders = new HashSet<Downloader>();
	
	/** Parse the Properties file. Check whether we have the jars it refers to.
	 * If not, start fetching them.
	 * @param props The Properties parsed from the dependencies.properties file.
	 * @return The set of filenames needed if we can deploy immediately, in 
	 * which case the caller MUST deploy. */
	public synchronized MainJarDependencies handle(Properties props, int build) {
		try {
			return innerHandle(props, build);
		} catch (RuntimeException e) {
			broken = true;
			Logger.error(this, "MainJarDependencies parsing update dependencies.properties file broke: "+e, e);
			throw e;
		} catch (Error e) {
			broken = true;
			Logger.error(this, "MainJarDependencies parsing update dependencies.properties file broke: "+e, e);
			throw e;
		}
	}
	
	private synchronized MainJarDependencies innerHandle(Properties props, int build) {
		// FIXME support deletion placeholders.
		// I.e. when we remove a library we put a placeholder in to tell this code to delete it.
		// It's not acceptable to just delete stuff we don't know about.
		if(deploying) {
			Logger.error(this, "Already deploying?");
			return null;
		}
		clear(build);
		HashSet<String> processed = new HashSet<String>();
		File[] list = new File(".").listFiles(new FileFilter() {

			@Override
			public boolean accept(File arg0) {
				if(!arg0.isFile()) return false;
				// Ignore non-jars regardless of what the regex says.
				String name = arg0.getName().toLowerCase();
				if(!name.endsWith(".jar")) return false;
				// FIXME similar checks elsewhere, factor out?
				if(name.equals("freenet.jar") || name.equals("freenet.jar.new") || name.equals("freenet-stable-latest.jar") || name.equals("freenet-stable-latest.jar.new"))
					return false;
				return true;
			}
			
		});
outer:	for(String propName : props.stringPropertyNames()) {
			if(!propName.contains(".")) continue;
			String baseName = propName.split("\\.")[0];
			if(!processed.add(baseName)) continue;
			// Version is useful to have, but we actually check the hash.
			String version = props.getProperty(baseName+".version");
			if(version == null) {
				Logger.error(this, "dependencies.properties broken? missing version");
				broken = true;
				continue;
			}
			File filename = null;
			String s = props.getProperty(baseName+".filename");
			if(s != null) filename = new File(s);
			if(filename == null) {
				Logger.error(this, "dependencies.properties broken? missing filename");
				broken = true;
				continue;
			}
			FreenetURI maxCHK = null;
			s = props.getProperty(baseName+".key");
			if(s == null) {
				Logger.error(this, "dependencies.properties broken? missing "+baseName+".key");
				// Can't fetch it. :(
			} else {
				try {
					maxCHK = new FreenetURI(s);
				} catch (MalformedURLException e) {
					Logger.error(this, "Unable to parse CHK for "+baseName+": \""+s+"\": "+e, e);
					maxCHK = null;
				}
			}
			// FIXME where to get the proper folder from? That seems to be an issue in UpdateDeployContext as well...
			
			// Regex used for matching filenames.
			String regex = props.getProperty(baseName+".filename-regex");
			if(regex == null) {
				// Not a critical error. Just means we can't clean it up, and can't identify whether we already have a compatible jar.
				Logger.error(this, "No "+baseName+".filename-regex in dependencies.properties - we will not be able to clean up old versions of files, and may have to download the latest version unnecessarily");
				// May be fatal later on depending on what else we have.
			}
			Pattern p = null;
			try {
				if(regex != null)
					p = Pattern.compile(regex);
			} catch (PatternSyntaxException e) {
				Logger.error(this, "Bogus Pattern \""+regex+"\" in dependencies.properties");
				p = null;
			}
			
			byte[] expectedHash = parseExpectedHash(props.getProperty(baseName+".sha256"), baseName);
			if(expectedHash == null) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: No hash for "+baseName);
				broken = true;
				continue;
			}
			
			s = props.getProperty(baseName+".size");
			long size = -1;
			if(s != null) {
				try {
					size = Long.parseLong(s);
				} catch (NumberFormatException e) {
					size = -1;
				}
			}
			if(size < 0) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken length for "+baseName+" : \""+s+"\"");
				broken = true;
				continue;
			}
			
			// We need to determine whether it is in use at the moment.
			File currentFile = getDependencyInUse(baseName, p);
			
			if(validFile(filename, expectedHash, size)) {
				// Nothing to do. Yay!
				System.out.println("Found file required by the new Freenet version: "+filename);
				// Use it.
				dependencies.add(new Dependency(currentFile, filename, p));
				continue;
			}
			// Check the version currently in use.
			if(currentFile != null && validFile(currentFile, expectedHash, size)) {
				System.out.println("Existing version of "+currentFile+" is OK for update.");
				// Use it.
				dependencies.add(new Dependency(currentFile, currentFile, p));
				continue;
			}
			// We might be somewhere in between.
			if(p == null) {
				// No way to check existing files.
				if(maxCHK != null) {
					try {
						fetchDependencyEssential(maxCHK, new Dependency(currentFile, filename, p), expectedHash, size);
					} catch (FetchException fe) {
						broken = true;
						Logger.error(this, "Failed to start fetch: "+fe, fe);
						System.err.println("Failed to start fetch of essential component for next release: "+fe);
					}
				} else {
					// Critical error.
					System.err.println("Unable to fetch "+baseName+" because no URI and no regex to match old versions.");
					broken = true;
					continue;
				} 
				continue;
			}
			for(File f : list) {
				String name = f.getName();
				if(!p.matcher(name).matches()) continue;
				if(validFile(f, expectedHash, size)) {
					// Use it.
					System.out.println("Found "+name+" - meets requirement for "+baseName+" for next update.");
					dependencies.add(new Dependency(currentFile, f, p));
					continue outer;
				}
			}
			if(maxCHK == null) {
				System.err.println("Cannot fetch "+baseName+" for update because no CHK and no old file");
				broken = true;
				continue;
			}
			// Otherwise we need to fetch it.
			try {
				fetchDependencyEssential(maxCHK, new Dependency(currentFile, filename, p), expectedHash, size);
			} catch (FetchException e) {
				broken = true;
				Logger.error(this, "Failed to start fetch: "+e, e);
				System.err.println("Failed to start fetch of essential component for next release: "+e);
			}
		}
		if(ready())
			return new MainJarDependencies(new HashSet<Dependency>(dependencies), build);
		else
			return null;
	}
	
	/** Should be called on startup, before any fetches have started. Will 
	 * remove unnecessary files and start blob fetches for files we don't 
	 * have blobs for.
	 * @param props The dependencies.properties from the running version.
	 * @return True unless something went wrong.
	 */
	public static boolean cleanup(Properties props, Deployer deployer, int build) {
		// This method should not change anything, but can call the callbacks.
		HashSet<String> processed = new HashSet<String>();
		File[] listMain = new File(".").listFiles(new FileFilter() {

			@Override
			public boolean accept(File arg0) {
				if(!arg0.isFile()) return false;
				// Ignore non-jars regardless of what the regex says.
				String name = arg0.getName().toLowerCase();
				if(!name.endsWith(".jar")) return false;
				// FIXME similar checks elsewhere, factor out?
				if(name.equals("freenet.jar") || name.equals("freenet.jar.new") || name.equals("freenet-stable-latest.jar") || name.equals("freenet-stable-latest.jar.new"))
					return false;
				return true;
			}
			
		});
		for(String propName : props.stringPropertyNames()) {
			if(!propName.contains(".")) continue;
			String baseName = propName.split("\\.")[0];
			if(!processed.add(baseName)) continue;
			// Version is useful for checking for obsolete versions of files.
			String version = props.getProperty(baseName+".version");
			if(version == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing version");
				return false;
			}
			File filename = null;
			String s = props.getProperty(baseName+".filename");
			if(s != null) filename = new File(s);
			if(filename == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing filename");
				return false;
			}
			
			// Check key even though we don't use it.
			final FreenetURI key;
			s = props.getProperty(baseName+".key");
			if(s == null) {
				Logger.error(MainJarDependencies.class, "dependencies.properties broken? missing "+baseName+".key");
				return false;
			}
			try {
				key = new FreenetURI(s);
			} catch (MalformedURLException e) {
				Logger.error(MainJarDependencies.class, "Unable to parse CHK for "+baseName+": \""+s+"\": "+e, e);
				return false;
			}
			
			// Regex used for matching filenames.
			String regex = props.getProperty(baseName+".filename-regex");
			if(regex == null) {
				Logger.error(MainJarDependencies.class, "No "+baseName+".filename-regex in dependencies.properties");
				return false;
			}
			Pattern p;
			try {
				p = Pattern.compile(regex);
			} catch (PatternSyntaxException e) {
				Logger.error(MainJarDependencies.class, "Bogus Pattern \""+regex+"\" in dependencies.properties");
				return false;
			}
			
			byte[] expectedHash = parseExpectedHash(props.getProperty(baseName+".sha256"), baseName);
			if(expectedHash == null) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: No hash for "+baseName);
				return false;
			}
			
			s = props.getProperty(baseName+".size");
			long size = -1;
			if(s != null) {
				try {
					size = Long.parseLong(s);
				} catch (NumberFormatException e) {
					size = -1;
				}
			}
			if(size < 0) {
				System.err.println("Unable to update to build "+build+": dependencies.properties broken: Broken length for "+baseName+" : \""+s+"\"");
				return false;
			}
			
			File currentFile = getDependencyInUse(baseName, p);
			
			// Serve the file if it meets the hash in the dependencies.properties.
			if(currentFile != null && currentFile.exists()) {
				if(validFile(currentFile, expectedHash, size)) {
					System.out.println("Will serve "+filename+" for UOM");
					deployer.addDependency(expectedHash, filename);
				} else {
					System.out.println("Component "+baseName+" is using a non-standard file, we cannot serve the file "+filename+" via UOM to other nodes. Hence they may not be able to download the update from us.");
				}
			} else {
				// Not present even though it's required.
				// This means we want to preload it before the next release.
				final File file = filename;
				try {
					System.out.println("Preloading "+filename+" for the next update...");
					deployer.fetch(key, filename, size, expectedHash, new JarFetcherCallback() {

						@Override
						public void onSuccess() {
							System.out.println("Preloaded "+file+" which will be needed when we upgrade.");
						}

						@Override
						public void onFailure(FetchException e) {
							Logger.error(this, "Failed to preload "+file+" from "+key+" : "+e, e);
						}
						
					}, build);
				} catch (FetchException e) {
					Logger.error(MainJarDependencies.class, "Failed to preload "+file+" from "+key+" : "+e, e);
				}
			}
			
			// Now delete bogus dependencies.
			for(File f : listMain) {
				String name = f.getName();
				if(!p.matcher(name).matches()) continue;
				if(f.equals(currentFile)) continue;
				String fileVersion = getDependencyVersion(f);
				if(fileVersion == null) {
					f.delete();
					System.out.println("Deleting old dependency file (no version): "+f);
				}
				if(Fields.compareVersion(fileVersion, version) <= 0) {
					f.delete();
					System.out.println("Deleting old dependency file (outdated): "+f);
				} // Keep newer versions.
			}
		}
		return true;
	}
	
    public static String getDependencyVersion(File currentFile) {
        // We can't use parseProperties because there are multiple sections.
    	InputStream is = null;
        try {
        	is = new FileInputStream(currentFile);
        	ZipInputStream zis = new ZipInputStream(is);
        	ZipEntry ze;
        	while(true) {
        		ze = zis.getNextEntry();
        		if(ze == null) break;
        		if(ze.isDirectory()) continue;
        		String name = ze.getName();
        		
        		if(name.equals("META-INF/MANIFEST.MF")) {
        			BufferedInputStream bis = new BufferedInputStream(zis);
        			// Java 1.6 Properties never, ever throws EOFException.
        			// Let's make sure...
        			EOFInputStream eof = new EOFInputStream(bis);
        			while(true) {
        				// And check available() too, although who knows whether it's reliable.
        				if(eof.available() == 0) break;
        				Properties props = new Properties();
        				props.load(eof);
        				String version = props.getProperty("Implementation-Version");
        				if(version != null) return version;
        			}
        		}
        	}
        	Logger.error(MainJarDependenciesChecker.class, "Unable to get dependency version from "+currentFile);
        	return null;
        } catch (FileNotFoundException e) {
        	return null;
        } catch (IOException e) {
        	return null;
        } finally {
        	Closer.close(is);
        }
	}

    /** Find the current filename, on the classpath, of the dependency given.
     * Note that this may not actually exist, and the caller should check!
     * However, even a non-existent filename may be useful when updating 
     * wrapper.conf.
     */
	private static File getDependencyInUse(String baseName, Pattern p) {
		String classpath = System.getProperty("java.class.path");
		String[] split = classpath.split(File.pathSeparator);
		for(String s : split) {
			File f = new File(s);
			if(p.matcher(f.getName()).matches())
				return f;
		}
		return null;
	}

	private static byte[] parseExpectedHash(String sha256, String baseName) {
		if(sha256 == null) {
			Logger.error(MainJarDependencies.class, "No SHA256 for "+baseName+" in dependencies.properties");
			return null;
		}
		try {
			return HexUtil.hexToBytes(sha256);
			// FIXME change these exceptions to something caught?
		} catch (NumberFormatException e) {
			Logger.error(MainJarDependencies.class, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return null;
		} catch (IndexOutOfBoundsException e) {
			Logger.error(MainJarDependencies.class, "Bogus expected hash: \""+sha256+"\" : "+e, e);
			return null;
		}
	}

	public static boolean validFile(File filename, byte[] expectedHash, long size) {
		if(filename == null) return false;
		if(!filename.exists()) return false;
		if(filename.length() != size) {
			System.out.println("File exists while updating but length is wrong ("+filename.length()+" should be "+size+") for "+filename);
			return false;
		}
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(filename);
			MessageDigest md = SHA256.getMessageDigest();
			SHA256.hash(fis, md);
			byte[] hash = md.digest();
			SHA256.returnMessageDigest(md);
			fis.close();
			fis = null;
			if(Arrays.equals(hash, expectedHash))
				return true;
			else {
				System.out.println("File exists in update but bad hash: "+filename+" - deleting");
				filename.delete();
				return false;
			}
		} catch (FileNotFoundException e) {
			Logger.error(MainJarDependencies.class, "File not found: "+filename);
			return false;
		} catch (IOException e) {
			System.err.println("Unable to read "+filename+" for updater");
			return false;
		} finally {
			Closer.close(fis);
		}
	}

	private synchronized void clear(int build) {
		dependencies.clear();
		broken = false;
		this.build = build;
		for(Downloader d : downloaders)
			d.cancel();
		downloaders.clear();
	}

	/** Unlike other methods here, this should be called outside the lock. */
	public void deploy() {
		HashSet<Dependency> f;
		synchronized(this) {
			f = new HashSet<Dependency>(dependencies);
		}
		deployer.deploy(new MainJarDependencies(f, build));
	}

	private synchronized void fetchDependencyEssential(FreenetURI chk, Dependency dep, byte[] expectedHash, long expectedSize) throws FetchException {
		Downloader d = new Downloader(dep, chk, expectedHash, expectedSize);
		downloaders.add(d);
	}

	private synchronized boolean ready() {
		if(broken) return false;
		if(!downloaders.isEmpty()) return false;
		deploying = true;
		return true;
	}
	
	public synchronized boolean isBroken() {
		return broken;
	}

}
