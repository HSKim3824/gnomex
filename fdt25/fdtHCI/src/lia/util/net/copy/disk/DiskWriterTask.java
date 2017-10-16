/*
 * $Id$
 */
package lia.util.net.copy.disk;

//import gui.FdtMain;
//import gui.GUIFileStatus;
//import gui.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.SimpleDateFormat;

import lia.util.net.common.Utils;
import lia.util.net.copy.FDTSession;
import lia.util.net.copy.FDTSessionManager;
import lia.util.net.copy.FileBlock;
import lia.util.net.copy.FileSession;
import lia.util.net.common.Config;
//import lia.util.net.copy.FDT;
/**
 * per partition DiskWriterTask .... ( there may be more than one writer per
 * partition )
 *
 * @author ramiro
 */
public class DiskWriterTask extends GenericDiskTask {

    private static final Logger logger = Logger.getLogger(DiskWriterTask.class.getName());

    private static final DiskWriterManager dwm = DiskWriterManager.getInstance();

    private static final FDTSessionManager fsm = FDTSessionManager.getInstance();

    private final Lock countersRLock;

    private final Lock countersWLock;

    long sTime;

    long sTimeWrite;

    long sTimeFinish;

    long finishTime;

    public long dtTake;

    public long dtWrite;

    public long dtFinishSession;

    public long dtTotal;

    private static final Config config = Config.getInstance();

    final BlockingQueue<FileBlock> queue;

    private final AtomicBoolean hasToRun;

    private final boolean doNotForceOnClose;

    DiskWriterTask(int partitionID, int writerID, BlockingQueue<FileBlock> queue) {
        super(partitionID, writerID);
        this.queue = queue;
        hasToRun = new AtomicBoolean(true);
        final ReadWriteLock rwl = new ReentrantReadWriteLock();
        countersRLock = rwl.readLock();
        countersWLock = rwl.writeLock();
        final boolean hasP = (System.getProperty("fdt.doNotForceOnClose") != null);
        doNotForceOnClose = (hasP) ? Boolean.getBoolean("fdt.doNotForceOnClose") : true;
    }

    public void stopIt() {

        if (hasToRun.compareAndSet(true, false)) {
            logger.log(Level.INFO, this.myName + " STOPPED!");

        }

    }

    public final Lock getCountersRLock() {
        return countersRLock;
    }

    public int partitionID() {
        return partitionID;
    }

    public BlockingQueue<FileBlock> queue() {
        return queue;
    }

    public final int writerID() {
        return taskID;
    }
    
    private void logToAppLogger(FileSession fileSession) {
//      if (config.getAppLogger() != null && !config.getAppLogger().equals("")) {

          // for testing just show what the command would have been
          SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS a");
          String cmd = "/bin/sh " + config.getAppLogger() + " -fileName " + fileSession.getFile().getAbsolutePath() +
                  " -type " + "upload" + " -method " + "fdt" + " -startDateTime " +
                  df.format(new java.util.Date(fileSession.startTimeMillis)) + " -endDateTime " + df.format(new java.util.Date(System.currentTimeMillis())) +
                  " -fileSize " + Long.valueOf(fileSession.getFile().length()).toString();

          System.out.println("\n[DiskWriterTask:logToAppLogger] " + cmd + "\n");
/*
          try {
          df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS a");
          Process process = Runtime.getRuntime().exec( new String[] {"/bin/sh", config.getAppLogger(),
              "-fileName", fileSession.getFile().getAbsolutePath(), 
              "-type", "upload",
              "-method", "fdt", 
              "-startDateTime", df.format(new java.util.Date(fileSession.startTimeMillis)),
              "-endDateTime", df.format(new java.util.Date(System.currentTimeMillis())),
              "-fileSize", Long.valueOf(fileSession.getFile().length()).toString()
              });          
          process.waitFor();
          process.destroy();

        } catch(Exception e) {
          logger.log(Level.WARNING, " \n\n\n Cannot write to app logger " + config.getAppLogger() + " Cause: ", e);
        }
      }
*/
 //     }
    }
    
    public void run() {

        final String cName = Thread.currentThread().getName();
        this.myName = new StringBuilder("DiskWriterTask [ partitionID=").append(this.partitionID)
                .append(", writerID= ").append(this.taskID).append(", tid=").append(Thread.currentThread().getId())
                .append(" ] since: ").append(new java.util.Date()).toString();

        try {
            Thread.currentThread().setName(myName);
        } catch (Throwable t1) {
            logger.log(Level.SEVERE, "Got exception trying to set thread name for DiskWriterTask", t1);
        }

        int writtenBytes = -1;

        final BlockingQueue<FileBlock> queue = this.queue;

        logger.log(Level.INFO, myName + " STARTED. Active = " + hasToRun.get());

        while (hasToRun.get()) {

            FileBlock fileBlock = null;
            FileChannel fileChannel = null;
            FileSession fileSession = null;
            FDTSession fdtSession = null;

            try {
                sTime = System.nanoTime();
                sTimeFinish = 0;

                fileBlock = queue.poll(10, TimeUnit.SECONDS);

                if ((fileBlock == null) || (fileBlock.buff == null)) {// Timeout
                    if (hasToRun.get()) {
                        continue;
                    }

                    break;
                }

                fdtSession = fsm.getSession(fileBlock.fdtSessionID);

                if (fdtSession == null) {// way to fast ...
                    logger.log(Level.WARNING, myName + " Got a fileBlock for fdtSessionID: " + fileBlock.fdtSessionID
                            + " but the session does not appear to be in the manager's map");
                    continue;
                }

                fileSession = fdtSession.getFileSession(fileBlock.fileSessionID);

                if (fileSession == null) {
                    logger.log(Level.WARNING, " No such fileSession in local map [ fileSessionID: "
                            + fileBlock.fileSessionID + ", fdtSessionID: " + fileBlock.fdtSessionID + " ]");
                    continue;
                }

                sTimeWrite = System.nanoTime();

                fileChannel = fileSession.getChannel();
                if (fileChannel != null) {
                    writtenBytes = -1;

                    final int remainingBeforeWrite = fileBlock.buff.remaining();

                    if (!fileSession.isLoop()) {
                        writtenBytes = fileChannel.write(fileBlock.buff, fileBlock.fileOffset);
                    } else {
                        writtenBytes = fileChannel.write(fileBlock.buff);
                    }

                    // if(fileSession.shouldFlush()) {
                    // fileChannel.force(false);
                    // }

                    // TODO - Is this even possible? If yes ??!? ... is it
                    // something going wrong with Kernel/FS???
                    // In fact it was possible ... @ Caltech with cithep212 (
                    // RHEL/kernel issue )
                    if (fileBlock.buff.hasRemaining()) {
                        final File f = fileSession.getFile();
                        long freeSpace = -1;
                        long totalSpace = -1;
                        long usableSpace = -1;
                        final File fp = ((f != null) && f.exists()) ? f : f.getParentFile();

                        if (fp != null) {
                            freeSpace = fp.getFreeSpace();
                            totalSpace = fp.getTotalSpace();
                            usableSpace = fp.getUsableSpace();
                        }

                        String ratio = "";
                        boolean isFull = false;
                        if (totalSpace > 0L) {
                            final double freeSpaceRatio = freeSpace / totalSpace;
                            final double usableSpaceRatio = usableSpace / totalSpace;
                            ratio += "freeSpaceRatio: " + Utils.percentDecimalFormat(freeSpaceRatio)
                                    + "% usableSpaceRatio: " + Utils.percentDecimalFormat(usableSpaceRatio) + "%";
                            if ((freeSpace < fileBlock.buff.capacity()) || (usableSpace < fileBlock.buff.capacity())
                                    || (freeSpaceRatio < 5d) || (usableSpaceRatio < 5d)) {
                                isFull = true;
                                ratio += "\n\n Not enough space to write the buffers on current partition!";
                            }
                        } else {
                            ratio += " totalSpace: " + totalSpace
                                    + " <= 0 BYTES; free/usable ratio cannot be computed !";
                        }

                        String cause = "";
                        if (!isFull) {
                            cause = "\n\n\n [ ERROR ] "
                                    + myName
                                    + " buffer still hasRemaining() for file: "
                                    + f
                                    + "\n"
                                    + "\n The disk partition may be full or there is a BUG in FileSystem/Kernel/OS/Java NIO !! \n"
                                    + "\n\n Disk partition statistics for "
                                    + fp
                                    + ":\n"
                                    + "\n Total free/usable/total space: "
                                    + freeSpace
                                    + " / "
                                    + usableSpace
                                    + " / "
                                    + totalSpace
                                    + " bytes"
                                    + "\n "
                                    + ratio
                                    + "\n\n"
                                    + "\n Please note that the partial file will be deleted and space may be available after the session finishes! \n\n"
                                    + "\n fileblock offset = " + fileBlock.fileOffset
                                    + "\n buff.remaining() before write: " + remainingBeforeWrite
                                    + "\n buff.remaining() after write: " + fileBlock.buff.remaining()
                                    + "\n new position = " + fileChannel.position() + "\n written bytes = "
                                    + writtenBytes + "\n\n\n";
                        } else {
                            cause = "\n\n\n [ ERROR ] "
                                    + myName
                                    + "\n The disk partition for: "
                                    + fp
                                    + " may be full \n"
                                    + "\n Total free/usable/total space: "
                                    + freeSpace
                                    + " / "
                                    + usableSpace
                                    + " / "
                                    + totalSpace
                                    + " bytes"
                                    + "\n "
                                    + ratio
                                    + "\n\n"
                                    + "\n Please note that the partial file will be deleted and space may be available after the session finishes! \n\n";
                        }

                        fdtSession.close(cause, new IOException(cause));
                        continue;
                    }

                    if (writtenBytes == -1) {
                        sTimeFinish = System.nanoTime();
                        logger.log(Level.WARNING, "\n\n [ ERROR ] " + myName + " ... Unable to write bytes to [  ( "
                                + fileSession.sessionID() + " ): " + fileSession.fileName()
                                + " ] Disk full or R/O partition ?");
                        Throwable downCause = new IOException(
                                "Unable to write bytes ????  [ Full disk or R/O partition ]");
                        downCause.fillInStackTrace();
                        fsm.getSession(fileBlock.fdtSessionID).finishFileSession(fileSession.sessionID(), downCause);
                    } else {
                        fileSession.cProcessedBytes.addAndGet(writtenBytes);

                        dwm.addAndGetTotalBytes(writtenBytes);
                        dwm.addAndGetUtilBytes(writtenBytes);

                        addAndGetTotalBytes(writtenBytes);
                        addAndGetUtilBytes(writtenBytes);

                        fdtSession.addAndGetTotalBytes(writtenBytes);
                        fdtSession.addAndGetUtilBytes(writtenBytes);

                    }

                    // forcing file channel close() - hadoop issues
                    if (fileSession.cProcessedBytes.get() == fileSession.sessionSize()) {// EOF
                        if (!fdtSession.loop()) {
                            try {
                                if (!fileSession.isNull() && !fileSession.isZero()) {
                                    if (doNotForceOnClose) {
                                        if (logger.isLoggable(Level.FINER)) {
                                            logger.log(Level.FINER,
                                                    "CLOSE - Not enforcing flush - " + fileSession.getFile()
                                                    + " closing without forcing the channel");
                                        }
                                    } else {
                                        fileSession.getChannel().force(true);
                                    }
                                }
                            } catch (Throwable t1) {
                                logger.log(Level.WARNING, myName
                                        + " got exception forcing data to  writer channel for file writer session "
                                        + fileSession, t1);
                            }

                            // let this one throw an exception
                            fileSession.getChannel().close();
                        }

                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE, "\n " + myName + " ... All the bytes ( " + fileSession.sessionSize()
                                    + " ) for [  ( " + fileSession.sessionID() + " ): " + fileSession.fileName()
                                    + " ] have been written ");
                        }
                        //
                        // Plug-in point for application transfer logging
                        //
//                        if (FdtMain.isIsServerMode()) {
                          logToAppLogger(fileSession);
//                        }

                        fileSession.close(null, null);

                        sTimeFinish = System.nanoTime();

                    }

                } else {
                    Throwable downCause = new NullPointerException("Null File Channel inside disk writer worker [ "
                            + myName + " ] for [  ( " + fileSession.sessionID() + " ): " + fileSession.fileName()
                            + " ]");
                    downCause.fillInStackTrace();
                    sTimeFinish = System.nanoTime();
                    fsm.getSession(fileBlock.fdtSessionID).finishFileSession(fileSession.sessionID(), downCause);
                }

                finishTime = System.nanoTime();

                countersWLock.lock();
                try {
                    dtTotal += (finishTime - sTime);
                    dtTake += (sTimeWrite - sTime);
                    if (sTimeFinish != 0) {
                        dtWrite += (sTimeFinish - sTimeWrite);
                        dtFinishSession += (finishTime - sTimeFinish);
                    } else {
                        dtWrite += (finishTime - sTimeWrite);
                    }
                } finally {
                    countersWLock.unlock();
                }

            } catch (IOException ioe) {
                sTimeFinish = System.nanoTime();
                logger.log(Level.SEVERE,
                        myName + " ... Got I/O Exception writing to file [  ( " + fileSession.sessionID() + " ): "
                                + fileSession.fileName() + " ] offset: " + fileBlock.fileOffset, ioe);
                if (fileSession.sessionID() != null) {
                    fdtSession.finishFileSession(fileSession.sessionID(), ioe);
                }
            } catch (InterruptedException ie) {
                if (fileSession == null) {
                    logger.log(
                            Level.SEVERE,
                            myName
                            + " ... Got InterruptedException Exception writing to file [  ( fileSession is null ) ] offset: "
                            + ((fileBlock == null) ? " fileBlock is null" : "" + fileBlock.fileOffset), ie);
                } else {
                    logger.log(Level.SEVERE, myName + " ... Got InterruptedException Exception writing to file [  ( "
                            + fileSession.sessionID() + " ): " + fileSession.fileName() + " ] offset: "
                            + ((fileBlock == null) ? " fileBlock is null" : "" + fileBlock.fileOffset), ie);
                }
            } catch (Throwable t) {
                sTimeFinish = System.nanoTime();
                if (fileSession == null) {
                    logger.log(
                            Level.SEVERE,
                            myName
                            + " ... Got GeneralException Exception writing to file [  ( fileSession is null ) ] offset: "
                            + ((fileBlock == null) ? " fileBlock is null" : "" + fileBlock.fileOffset), t);
                } else {
                    logger.log(Level.SEVERE, myName + " ... Got GeneralException Exception writing to file [  ( "
                            + fileSession.sessionID() + " ): " + fileSession.fileName() + " ] offset: "
                            + ((fileBlock == null) ? " fileBlock is null" : "" + fileBlock.fileOffset), t);
                }

                if ((fdtSession != null) && (fileSession.sessionID() != null)) {
                    fdtSession.finishFileSession(fileSession.sessionID(), t);
                }
            } finally {
                try {
                    if ((fileBlock != null) && (fileBlock.buff != null)) {
                        bufferPool.put(fileBlock.buff);
                    }
                    fileBlock = null;
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, myName + " ... unable to return the buffer to the bufferPool", t);
                }
            }

        }// while()

        try {
            Utils.drainFileBlockQueue(queue);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Possbile buff loss from the pool", t);
        }

        try {
            Thread.currentThread().setName(cName);
        } catch (Throwable t) {
            // does not matter if did not succeed
        }

        stopIt();

        logger.log(Level.INFO, myName + " STOPPED! hasToRun() = " + hasToRun.get());
    }// run()
}
