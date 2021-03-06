use gnomex;

-- Add property for DataTrack directory
insert into PropertyDictionary (propertyName, propertyValue, propertyDescription, forServerOnly) 
values ('datatrack_read_directory', '', 'The filepath to datatracks not associated with Analysis files', 'N');
insert into PropertyDictionary (propertyName, propertyValue, propertyDescription, forServerOnly) 
values ('datatrack_write_directory', '', 'The filepath to datatracks not associated with Analysis files', 'N');

--
-- Table structure for table `DataTrackFile`
--
DROP TABLE IF EXISTS `DataTrackFile`;
CREATE TABLE `DataTrackFile` (
  `idDataTrackFile` int(10)  NOT NULL auto_increment,
  `idAnalysisFile` int(10)   NULL,
  `idDataTrack` int(10)  NULL,
  PRIMARY KEY  (`idDataTrackFile`),
  KEY `FK_DataTrackFile_DataTrack` (`idDataTrack`),
  KEY `FK_DataTrackFile_AnalysisFile` (`idAnalysisFile`),
  CONSTRAINT `FK_DataTrackFile_DataTrack` FOREIGN KEY (`idDataTrack`) REFERENCES `DataTrack` (`idDataTrack`),
  CONSTRAINT `FK_AnalysisFile_DataTrack` FOREIGN KEY (`idAnalysisFile`) REFERENCES `AnalysisFile` (`idAnalysisFile`)
) ENGINE=InnoDB;


update gnomex.Property set forSample = 'Y';
update gnomex.Property set forDataTrack = 'N';
update gnomex.Property set forAnalysis = 'N';
