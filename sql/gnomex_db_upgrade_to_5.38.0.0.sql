use gnomex;


-----------------------------------
-- Create table PipelineProtocol --
-----------------------------------

DROP TABLE IF EXISTS PipelineProtocol;
CREATE TABLE PipelineProtocol (
  idPipelineProtocol [int] IDENTITY(1,1) NOT NULL,
  description LONGTEXT NULL,
  idCoreFacility INT NOT NULL,
  protocol VARCHAR(50) NOT NULL,
  isDefault VARCHAR(1) NOT NULL DEFAULT 'N',
 CONSTRAINT [PK_AppUser] PRIMARY KEY CLUSTERED
(
	[idAppUser] ASC
)WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF, IGNORE_DUP_KEY = OFF, ALLOW_ROW_LOCKS = ON, ALLOW_PAGE_LOCKS = ON, FILLFACTOR = 90) ON [PRIMARY]
) ON [PRIMARY]

GO

SET ANSI_PADDING OFF
GO




  PRIMARY KEY (idPipelineProtocol),
  CONSTRAINT FK_PipelineProtocol_CoreFacility FOREIGN KEY FK_PipelineProtocol_CoreFacility (idCoreFacility)
    REFERENCES gnomex.CoreFacility (idCoreFacility)
    ON DELETE NO ACTION
    ON UPDATE NO ACTION
)
ENGINE = INNODB;


------------------------------------------------------
-- Add FK column FlowCellChannel.idPipelineProtocol --
------------------------------------------------------

ALTER TABLE FlowCellChannel
ADD COLUMN idPipelineProtocol INT(10) NULL;

ALTER TABLE FlowCellChannel ADD
  CONSTRAINT FK_FlowCellChannel_PipelineProtocol FOREIGN KEY FK_FlowCellChannel_PipelineProtocol (idPipelineProtocol)
    REFERENCES gnomex.PipelineProtocol (idPipelineProtocol)
    ON DELETE NO ACTION
    ON UPDATE NO ACTION;

CALL ExecuteIfTableExists('gnomex','FlowCellChannel_Audit','ALTER TABLE FlowCellChannel_Audit ADD COLUMN idPipelineProtocol INT(10) NULL');



-----------------------------------------------
-- Drop column BillingTemplateItem.sortOrder --
-----------------------------------------------

ALTER TABLE BillingTemplateItem
DROP COLUMN sortOrder;

CALL ExecuteIfTableExists('gnomex', 'BillingTemplateItem_Audit', 'ALTER TABLE BillingTemplateItem_Audit DROP COLUMN sortOrder');

ALTER TABLE BillingTemplate ADD COLUMN isActive char(1) null;
CALL ExecuteIfTableExists('gnomex', 'BillingTemplate_Audit', 'ALTER TABLE BillingTemplate_Audit ADD COLUMN isActive char(1) null');
