package no.synth.divelog.ui.io

/**
 * The tables of a Shearwater Cloud database export, as Shearwater Cloud writes them, and
 * the schema version rows it keeps. An export we write uses them unchanged so Shearwater
 * Cloud opens it like one of its own.
 */
internal object ShearwaterCloudSchema {
    val TABLES: List<String> = listOf(
        "CREATE TABLE \"dive_details\"( \"DiveId\" varchar primary key not null , \"DataVersion\" bigint " +
            ", \"FileName\" varchar , \"LastModified\" datetime , \"MainTimestampReliable\" varchar , \"DiveDa" +
            "te\" datetime , \"Depth\" varchar , \"SerialNumber\" varchar , \"AverageDepth\" float , \"AverageT" +
            "emp\" float , \"MinTemp\" float , \"MaxTemp\" float , \"EndGF99\" float , \"DiveLengthTime\" varcha" +
            "r , \"Location\" varchar , \"Site\" varchar , \"Buddy\" varchar , \"DateAndTime\" varchar , \"DiveN" +
            "umber\" varchar , \"Environment\" varchar , \"EnvironmentNotes\" varchar , \"Visibility\" varchar" +
            " , \"Weather\" varchar , \"Conditions\" varchar , \"Platform\" varchar , \"AirTemperature\" varcha" +
            "r , \"GnssEntryLocation\" varchar , \"GnssExitLocation\" varchar , \"GnssCustomWaypoints\" varch" +
            "ar , \"TankProfileData\" varchar , \"Tank1PressureStart\" varchar , \"Tank1PressureEnd\" varchar" +
            " , \"Tank2PressureStart\" varchar , \"Tank2PressureEnd\" varchar , \"Tank3PressureStart\" varcha" +
            "r , \"Tank3PressureEnd\" varchar , \"Tank4PressureStart\" varchar , \"Tank4PressureEnd\" varchar" +
            " , \"AverageSAC\" varchar , \"TankSize\" varchar , \"GasNotes\" varchar , \"Weight\" varchar , \"Ge" +
            "arNotes\" varchar , \"Dress\" varchar , \"Apparatus\" varchar , \"ThermalComfort\" varchar , \"Wor" +
            "kload\" varchar , \"Problems\" varchar , \"Malfunctions\" varchar , \"Symptoms\" varchar , \"Expos" +
            "ureToAltitude\" varchar , \"Notes\" varchar , \"Other1\" varchar , \"Other2\" varchar , \"Other3\" " +
            "varchar , \"IssueNotes\" varchar , \"AveloNotes\" varchar )",
        "CREATE TABLE \"dive_log_records\"( \"id\" integer primary key autoincrement not null , \"diveLo" +
            "gId\" varchar , \"currentTime\" integer , \"currentDepth\" float , \"firstStopDepth\" integer , \"" +
            "ttsMins\" integer , \"averagePPO2\" float , \"fractionO2\" float , \"fractionHe\" float , \"firstS" +
            "topTime\" integer , \"currentNdl\" integer , \"systemByte\" integer , \"currentCircuitSetting\" i" +
            "nteger , \"currentCcrMode\" integer , \"waterTemp\" integer , \"errorAcks\" integer , \"errorFlag" +
            "s\" integer , \"gasSwitchNeeded\" integer , \"externalPPO2\" integer , \"setPointType\" integer ," +
            " \"circuitSwitchType\" integer , \"sensor1Millivolts\" integer , \"sensor2Millivolts\" integer ," +
            " \"sensor3Millivolts\" integer , \"batteryVoltage\" float , \"backlightCurrent\" integer , \"curr" +
            "entPPO2SetPoint\" integer , \"lightLevelReading\" integer , \"CNSPercent\" integer , \"decoCeili" +
            "ng\" integer , \"gf99\" integer , \"safeAscentDepth\" float , \"atPlusFive\" integer , \"aiSensor0" +
            "_BatteryLevel\" integer , \"aiSensor0_PressurePSI\" integer , \"aiSensor1_BatteryLevel\" intege" +
            "r , \"aiSensor1_PressurePSI\" integer , \"aiSensor2_BatteryLevel\" integer , \"aiSensor2_Pressu" +
            "rePSI\" integer , \"aiSensor3_BatteryLevel\" integer , \"aiSensor3_PressurePSI\" integer , \"gas" +
            "Time\" integer , \"RespiratoryMinuteVolume\" float , \"ascentRate\" float , \"BatteryPercentRema" +
            "ining\" integer , \"HpDilPressure\" integer , \"HpO2Pressure\" integer , \"RawBytes\" blob )",
        "CREATE TABLE \"dive_logs\"( \"id\" integer , \"diveId\" varchar primary key not null , \"PrimaryK" +
            "ey\" varchar , \"DiveAutoInc\" integer , \"number\" integer , \"gfMin\" integer , \"gfMax\" integer" +
            " , \"surfaceMins\" integer , \"imperialUnits\" integer , \"startBatteryVoltage\" float , \"startC" +
            "NS\" integer , \"startDate\" varchar , \"startO2SensorStatus\" integer , \"startLowSetPoint\" int" +
            "eger , \"startHighSetPoint\" integer , \"computerFirmware\" integer , \"maxDepth\" integer , \"ma" +
            "xDepthFloat\" float , \"maxTime\" integer , \"errorHistory\" integer , \"endBatteryVoltage\" floa" +
            "t , \"endCNS\" integer , \"endDate\" varchar , \"endO2SensorStatus\" integer , \"endLowSetPoint\" " +
            "integer , \"endHighSetPoint\" integer , \"computerSerial\" integer , \"computerSoftwareVersion\"" +
            " integer , \"computerModel\" integer , \"logVersion\" integer , \"dbVersion\" integer , \"startTi" +
            "mestamp\" integer , \"endTimeStamp\" integer , \"decoModel\" integer , \"vpmbConservatism\" integ" +
            "er , \"startSurfacePressure\" integer , \"endSurfacePressure\" integer , \"sensorDisplay\" integ" +
            "er , \"product\" integer , \"features\" integer , \"startGFS\" integer , \"startsensor1Calibrated" +
            "\" integer , \"startSensor2Calibrated\" integer , \"startSensor3Calibrated\" integer , \"startse" +
            "nsor1CalibrationValue\" integer , \"startsensor2CalibrationValue\" integer , \"startsensor3Cal" +
            "ibrationValue\" integer , \"startsensor1ADCOffset\" float , \"startsensor2ADCOffset\" float , \"" +
            "startsensor3ADCOffset\" float , \"startO2DecoMode\" integer , \"startO2TempGender\" integer , \"" +
            "startO2TempWeight\" integer , \"startBatteryGuageAvailable\" integer , \"startBatteryPercentag" +
            "e\" integer , \"startBatteryType\" integer , \"startBatterySetting\" integer , \"startBatteryWar" +
            "ningLevel\" integer , \"startBatteryCriticalLevel\" integer , \"startHSI_Trim_Location\" intege" +
            "r , \"startLogVersion\" integer , \"endsensor1Calibrated\" integer , \"endsensor2Calibrated\" in" +
            "teger , \"endsensor3Calibrated\" integer , \"endsensor1CalibrationValue\" integer , \"endsensor" +
            "2CalibrationValue\" integer , \"endsensor3CalibrationValue\" integer , \"endsensor1ADCOffset\" " +
            "float , \"endsensor2ADCOffset\" float , \"endsensor3ADCOffset\" float , \"endO2DecoMode\" intege" +
            "r , \"endO2TempGender\" integer , \"endO2TempWeight\" integer , \"endBatteryGuageAvailable\" int" +
            "eger , \"endBatteryPercentage\" integer , \"endBatteryType\" integer , \"endBatterySetting\" int" +
            "eger , \"endBatteryWarningLevel\" integer , \"endBatteryCriticalLevel\" integer , \"endHSI_Trim" +
            "_Location\" integer , \"endLogVersion\" integer , \"maxDescentRateMbarPerMinute\" float , \"maxA" +
            "scentRateMbarPerMinute\" float , \"avgDescentRateMbarPerMinute\" float , \"avgAscentRateMbarPe" +
            "rMinute\" float , \"startComputerSerialNumber\" bigint , \"timeZoneOffsetInMinutes\" integer , " +
            "\"daylightSavings\" integer , \"HeaderSurfaceTime\" integer , \"HeaderO2Sensor1Status\" integer " +
            ", \"HeaderO2Sensor2Status\" integer , \"HeaderO2Sensor3Status\" integer , \"HeaderOcGas0O2Perce" +
            "nt\" integer , \"HeaderOcGas1O2Percent\" integer , \"HeaderOcGas2O2Percent\" integer , \"HeaderO" +
            "cGas3O2Percent\" integer , \"HeaderOcGas4O2Percent\" integer , \"HeaderOcGas0HePercent\" intege" +
            "r , \"HeaderOcGas1HePercent\" integer , \"HeaderOcGas2HePercent\" integer , \"HeaderOcGas3HePer" +
            "cent\" integer , \"HeaderOcGas4HePercent\" integer , \"HeaderCcGas0O2Percent\" integer , \"Heade" +
            "rCcGas1O2Percent\" integer , \"HeaderCcGas2O2Percent\" integer , \"HeaderCcGas3O2Percent\" inte" +
            "ger , \"HeaderCcGas4O2Percent\" integer , \"HeaderCcGas0HePercent\" integer , \"HeaderCcGas1HeP" +
            "ercent\" integer , \"HeaderCcGas2HePercent\" integer , \"HeaderCcGas3HePercent\" integer , \"Hea" +
            "derCcGas4HePercent\" integer , \"HeaderSwitchUpSetting\" integer , \"HeaderSwitchUpDepth\" inte" +
            "ger , \"HeaderSwitchDownSetting\" integer , \"HeaderSwitchDownDepth\" integer , \"HeaderO2Senso" +
            "rMode\" integer , \"HeaderErrorFlags0\" varchar , \"HeaderErrorFlags1\" varchar , \"HeaderErrorF" +
            "lags2\" varchar , \"HeaderErrorFlags3\" varchar , \"HeaderErrorAcks0\" varchar , \"HeaderErrorAc" +
            "ks1\" varchar , \"HeaderErrorAcks2\" varchar , \"HeaderErrorAcks3\" varchar , \"HeaderCurrentEve" +
            "ntLogNumber\" integer , \"HeaderSolenoidDepthCompensation\" integer , \"HeaderOcMinimumPPO2\" f" +
            "loat , \"HeaderOcMaximumPPO2\" float , \"HeaderOcDecoPPO2\" float , \"HeaderCcMinimumPPO2\" floa" +
            "t , \"HeaderCcMaximumPPO2\" float , \"HeaderDecoViolatedDisplayed\" integer , \"HeaderLastStopD" +
            "epth\" integer , \"HeaderEndDiveDelay\" integer , \"HeaderClockFormat\" integer , \"HeaderTitleC" +
            "olor\" integer , \"HeaderShowOCOnlyPPO2\" integer , \"HeaderSalinity\" integer , \"HeaderCo2Temp" +
            "Enabled\" integer , \"HeaderCo2TempWarmupFlags\" integer , \"HeaderCo2TempReadyFlags\" integer " +
            ", \"HeaderCo2TempScrubberState\" integer , \"HeaderCo2TempCurrentRct\" integer , \"HeaderCo2Tem" +
            "pCurrentRst\" integer , \"HeaderCo2TempMinimumRct\" integer , \"HeaderCo2TempMinimumRctDiveMin" +
            "utes\" integer , \"HeaderCo2TempMinimumRst\" integer , \"HeaderCo2TempMinimumRstDiveMinutes\" i" +
            "nteger , \"HeaderMode\" integer , \"HeaderGasState\" integer , \"HeaderAI_Mode\" integer , \"Head" +
            "erGTR_Mode\" integer , \"HeaderAI_Unit\" integer , \"HeaderAI_T1_SerialNumber\" varchar , \"Head" +
            "erAI_T1_TankSize\" integer , \"HeaderAI_T1_MaxPressure\" integer , \"HeaderAI_T1_ReservePressu" +
            "re\" integer , \"HeaderAI_T1_On\" integer , \"HeaderAI_T1_Name\" varchar , \"HeaderAI_T2_SerialN" +
            "umber\" varchar , \"HeaderAI_T2_TankSize\" integer , \"HeaderAI_T2_MaxPressure\" integer , \"Hea" +
            "derAI_T2_ReservePressure\" integer , \"HeaderAI_T2_On\" integer , \"HeaderAI_T2_Name\" varchar " +
            ", \"HeaderAI_T3_SerialNumber\" varchar , \"HeaderAI_T3_MaxPressure\" integer , \"HeaderAI_T3_Re" +
            "servePressure\" integer , \"HeaderAI_T3_On\" integer , \"HeaderAI_T3_Name\" varchar , \"HeaderAI" +
            "_T4_SerialNumber\" varchar , \"HeaderAI_T4_MaxPressure\" integer , \"HeaderAI_T4_ReservePressu" +
            "re\" integer , \"HeaderAI_T4_On\" integer , \"HeaderAI_T4_Name\" varchar , \"HeaderSideMountSwit" +
            "chPressure\" integer , \"HeaderExtendedDiveSampleIndicator\" integer , \"HeaderOem\" integer , " +
            "\"HeaderLang\" integer , \"HeaderOCRecSubMode\" integer , \"HeaderTotalStackTimeInSeconds\" inte" +
            "ger , \"HeaderRemainingStackTimeInSeconds\" integer , \"HeaderTotalOnTimeInSeconds\" integer ," +
            " \"HeaderDepthAlertEnabled\" integer , \"HeaderDepthAlertValue\" float , \"HeaderTimeAlertEnabl" +
            "ed\" integer , \"HeaderTimeAlertValueInMinutes\" integer , \"HeaderLowNdlAlertEnabled\" integer" +
            " , \"HeaderLowNdlAlertValueInMinutes\" integer , \"FooterSurfaceTime\" integer , \"FooterTempUn" +
            "itSystem\" integer , \"InternalBatteryVoltage\" float , \"InternalBatteryVoltage_d100\" float ," +
            " \"FooterInternalBatteryVoltage_d100\" float , \"FooterO2Sensor1Status\" integer , \"FooterO2Se" +
            "nsor2Status\" integer , \"FooterO2Sensor3Status\" integer , \"FooterOcGas0O2Percent\" integer ," +
            " \"FooterOcGas1O2Percent\" integer , \"FooterOcGas2O2Percent\" integer , \"FooterOcGas3O2Percen" +
            "t\" integer , \"FooterOcGas4O2Percent\" integer , \"FooterOcGas0HePercent\" integer , \"FooterOc" +
            "Gas1HePercent\" integer , \"FooterOcGas2HePercent\" integer , \"FooterOcGas3HePercent\" integer" +
            " , \"FooterOcGas4HePercent\" integer , \"FooterCcGas0O2Percent\" integer , \"FooterCcGas1O2Perc" +
            "ent\" integer , \"FooterCcGas2O2Percent\" integer , \"FooterCcGas3O2Percent\" integer , \"Footer" +
            "CcGas4O2Percent\" integer , \"FooterCcGas0HePercent\" integer , \"FooterCcGas1HePercent\" integ" +
            "er , \"FooterCcGas2HePercent\" integer , \"FooterCcGas3HePercent\" integer , \"FooterCcGas4HePe" +
            "rcent\" integer , \"FooterSwitchUpSetting\" integer , \"FooterSwitchUpDepth\" integer , \"Footer" +
            "SwitchDownSetting\" integer , \"FooterSwitchDownDepth\" integer , \"FooterO2SensorMode\" intege" +
            "r , \"FooterSurfacePressure\" integer , \"FooterErrorFlags0\" varchar , \"FooterErrorFlags1\" va" +
            "rchar , \"FooterErrorFlags2\" varchar , \"FooterErrorFlags3\" varchar , \"FooterErrorAcks0\" var" +
            "char , \"FooterErrorAcks1\" varchar , \"FooterErrorAcks2\" varchar , \"FooterErrorAcks3\" varcha" +
            "r , \"FooterCurrentEventLogNumber\" integer , \"FooterSolenoidDepthCompensation\" integer , \"F" +
            "ooterOcMinimumPPO2\" integer , \"FooterOcMaximumPPO2\" integer , \"FooterOcDecoPPO2\" integer ," +
            " \"FooterCcMinimumPPO2\" integer , \"FooterCcMaximumPPO2\" integer , \"FooterDecoViolatedDispla" +
            "yed\" integer , \"FooterLastStopDepth\" integer , \"FooterEndDiveDelay\" integer , \"FooterClock" +
            "Format\" integer , \"FooterTitleColor\" integer , \"FooterShowOCOnlyPPO2\" integer , \"FooterSal" +
            "inity\" integer , \"FooterGfs\" integer , \"FooterCo2TempEnabled\" integer , \"FooterCo2TempWarm" +
            "upFlags\" integer , \"FooterCo2TempReadyFlags\" integer , \"FooterCo2TempScrubberState\" intege" +
            "r , \"FooterCo2TempCurrentRct\" integer , \"FooterCo2TempCurrentRst\" integer , \"FooterCo2Temp" +
            "MinimumRct\" integer , \"FooterCo2TempMinimumRctDiveMinutes\" integer , \"FooterCo2TempMinimum" +
            "Rst\" integer , \"FooterCo2TempMinimumRstDiveMinutes\" integer , \"FooterMode\" integer , \"Foot" +
            "erGasState\" integer , \"FooterAI_Mode\" integer , \"FooterGTR_Mode\" integer , \"FooterAI_Unit\"" +
            " integer , \"FooterAI_T1_SerialNumber\" varchar , \"FooterAI_T1_TankSize\" integer , \"FooterAI" +
            "_T1_MaxPressure\" integer , \"FooterAI_T1_ReservePressure\" integer , \"FooterAI_T1_On\" intege" +
            "r , \"FooterAI_T1_Name\" varchar , \"FooterAI_T2_SerialNumber\" varchar , \"FooterAI_T2_TankSiz" +
            "e\" integer , \"FooterAI_T2_MaxPressure\" integer , \"FooterAI_T2_ReservePressure\" integer , \"" +
            "FooterAI_T2_On\" integer , \"FooterAI_T2_Name\" varchar , \"FooterAI_T3_SerialNumber\" varchar " +
            ", \"FooterAI_T3_TankSize\" integer , \"FooterAI_T3_MaxPressure\" integer , \"FooterAI_T3_Reserv" +
            "ePressure\" integer , \"FooterAI_T3_On\" integer , \"FooterAI_T3_Name\" varchar , \"FooterAI_T4_" +
            "SerialNumber\" varchar , \"FooterAI_T4_TankSize\" integer , \"FooterAI_T4_MaxPressure\" integer" +
            " , \"FooterAI_T4_ReservePressure\" integer , \"FooterAI_T4_On\" integer , \"FooterAI_T4_Name\" v" +
            "archar , \"FooterSideMountSwitchPressure\" integer , \"FooterExtendedDiveSampleIndicator\" int" +
            "eger , \"FooterAverageDiveDepth\" integer , \"FooterDiveTimeInSeconds\" integer , \"LogChecksum" +
            "\" integer , \"FooterOem\" integer , \"FooterLang\" integer , \"FooterAverageSacPSIPerMin\" float" +
            " , \"FooterOCRecSubMode\" integer , \"FooterTotalStackTimeInSeconds\" integer , \"FooterRemaini" +
            "ngStackTimeInSeconds\" integer , \"FooterTotalOnTimeInSeconds\" integer , \"FooterDepthAlertEn" +
            "abled\" integer , \"FooterDepthAlertValue\" float , \"FooterTimeAlertEnabled\" integer , \"Foote" +
            "rTimeAlertValueInMinutes\" integer , \"FooterLowNdlAlertEnabled\" integer , \"FooterLowNdlAler" +
            "tValueInMinutes\" integer , \"sampleRateMs\" integer )",
        "CREATE TABLE \"DeletedLogs\"( \"DiveID\" varchar primary key not null , \"DeletedAsOf\" datetime" +
            " )",
        "CREATE TABLE \"log_data\"( \"log_id\" varchar primary key not null , \"table_version\" bigint , " +
            "\"created_unixtime\" bigint , \"modified_unixtime\" bigint , \"file_name\" varchar , \"format\" va" +
            "rchar , \"format_version\" integer , \"calculated_values_from_samples\" varchar , \"data_bytes_" +
            "1\" blob , \"data_bytes_2\" blob , \"data_bytes_3\" blob , \"data_bytes_4\" blob )",
        "CREATE TABLE \"SyncV3MetadataDiveDetail\"( \"Id\" varchar primary key not null , \"LastModified" +
            "Device\" varchar , \"LastModifiedServerTime\" datetime , \"CreatedDevice\" varchar , \"CreatedTi" +
            "me\" datetime , \"FieldTimeStampJson\" varchar , \"Version\" integer )",
        "CREATE TABLE \"SyncV3MetadataDelete\"( \"Id\" varchar primary key not null , \"LastModifiedDevi" +
            "ce\" varchar , \"LastModifiedServerTime\" datetime , \"CreatedDevice\" varchar , \"CreatedTime\" " +
            "datetime , \"Version\" integer )",
        "CREATE TABLE \"SWC_TableVersion\"( \"Id\" integer primary key autoincrement not null , \"DbVers" +
            "ion\" integer , \"Updated\" integer )",
        "CREATE TABLE \"SyncV3MetadataDiveLog\"( \"Id\" varchar primary key not null , \"LastModifiedDev" +
            "ice\" varchar , \"LastModifiedServerTime\" datetime , \"CreatedDevice\" varchar , \"CreatedTime\"" +
            " datetime )",
        "CREATE TABLE \"StoredDiveComputer\"( \"SerialNumber\" bigint primary key not null , \"Version\" " +
            "bigint , \"JsonData\" varchar )",
        "CREATE TABLE \"IdToDiveComputer\"( \"SerialNumber\" bigint primary key not null , \"Version\" bi" +
            "gint , \"DiveComputerConnectionId\" varchar )",
        "CREATE TABLE \"CustomDiveComputer\"( \"SerialNumber\" bigint primary key not null , \"Version\" " +
            "bigint , \"UpdateTime\" datetime , \"JsonData\" varchar )",
        "CREATE UNIQUE INDEX \"DeletedLogs_DiveID\" on \"DeletedLogs\"(\"DiveID\")",
        "CREATE INDEX dive_log_records_diveLogId ON dive_log_records(diveLogId)",
        "CREATE INDEX dive_logs_diveLogId ON dive_logs(diveId)",
        "CREATE UNIQUE INDEX \"StoredDiveComputer_SerialNumber\" on \"StoredDiveComputer\"(\"SerialNumbe" +
            "r\")",
        "CREATE UNIQUE INDEX \"IdToDiveComputer_SerialNumber\" on \"IdToDiveComputer\"(\"SerialNumber\")",
        "CREATE UNIQUE INDEX \"CustomDiveComputer_SerialNumber\" on \"CustomDiveComputer\"(\"SerialNumbe" +
            "r\")",
        "CREATE TABLE \"dive_site\"( \"Guid\" varchar primary key not null , \"GeoHash\" varchar , \"Name\"" +
            " varchar , \"Location\" varchar , \"Region\" varchar , \"Country\" varchar , \"CountryCode\" varch" +
            "ar , \"Latitude\" float , \"Longitude\" float , \"timestamps\" varchar , \"model_version\" integer" +
            " , \"created_at\" bigint , \"modified_at\" bigint )",
    )

    /** Id, DbVersion, Updated. */
    val VERSIONS: List<Triple<Long, Long, Long>> = listOf(
        Triple(0, 10, 9),
        Triple(1, 1, 0),
        Triple(2, 2, 1),
        Triple(3, 3, 2),
        Triple(4, 4, 3),
        Triple(5, 5, 4),
        Triple(6, 6, 5),
        Triple(7, 7, 6),
        Triple(8, 8, 7),
        Triple(9, 9, 8),
        Triple(10, 11, 10),
        Triple(11, 12, 11),
    )
}
