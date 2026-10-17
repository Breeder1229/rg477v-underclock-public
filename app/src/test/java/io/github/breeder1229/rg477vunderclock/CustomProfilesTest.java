package io.github.breeder1229.rg477vunderclock;

import com.anbernic.control.underclock.shared.CustomProfileCommand;
import com.anbernic.control.underclock.shared.CustomProfileStore;
import com.anbernic.control.underclock.shared.CustomProfileValues;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CustomProfilesTest {
    private static CustomProfileValues minimum(){return CustomProfileValues.of(700000,1000000,1100000,403000000);}
    private static void reject(Runnable operation){try{operation.run();fail("Invalid input accepted");}catch(IllegalArgumentException expected){}}
    @Test public void boundsAndEveryGpuStepUseTheDeviceOppTable() {
        assertEquals(57,minimum().gpuOppIndex());
        assertEquals(0,CustomProfileValues.of(2200000,3200000,3350000,1400000000).gpuOppIndex());
        int[] gpu=CustomProfileValues.options(3);
        for(int i=0;i<gpu.length;i++)assertEquals(57-i,CustomProfileValues.of(700000,1000000,1100000,gpu[i]).gpuOppIndex());
        assertEquals(47,CustomProfileValues.of(900000,1200000,1400000,601000000).gpuOppIndex());
        assertEquals(23,CustomProfileValues.of(1800000,2660000,2700000,1029000000).gpuOppIndex());
        assertArrayEquals(new int[]{0x00404000,700000,0x00404100,1000000,0x00404200,1100000,0x00c04000,57},minimum().resources());
        reject(()->CustomProfileValues.of(600000,1000000,1100000,403000000));
        reject(()->CustomProfileValues.of(700000,960000,1100000,403000000));
        reject(()->CustomProfileValues.of(700000,2700000,1100000,403000000));
        reject(()->CustomProfileValues.of(700000,1000000,1120000,403000000));
        reject(()->CustomProfileValues.of(700000,1000000,1100000,400000000));
        reject(()->CustomProfileValues.of(2200001,3200000,3350000,1400000000));
    }
    @Test public void mutableArraysCannotChangeValidatedValues() {
        int[] input={700000,1000000,1100000,403000000};CustomProfileValues value=CustomProfileValues.of(input);
        input[0]=1;value.toArray()[1]=2;CustomProfileValues.options(2)[0]=3;value.resources()[1]=4;
        assertEquals(minimum(),value);assertEquals(1100000,CustomProfileValues.options(2)[0]);
        assertEquals(value,CustomProfileValues.decode(value.encode()));
        reject(()->CustomProfileValues.decode("700000,1000000,1100000,9999999999"));
        reject(()->CustomProfileValues.decode("700000,1000000,1100000,403000000,"));
        reject(()->CustomProfileValues.decode("+700000,1000000,1100000,403000000"));
    }
    @Test public void commandsRoundTripAllSlotsAndRejectAmbiguity() {
        for(int i=0;i<8;i++)assertEquals(i,CustomProfileCommand.parse(CustomProfileCommand.selection("123",i)).displayIndex);
        for(int slot=0;slot<4;slot++) {
            CustomProfileCommand parsed=CustomProfileCommand.parse(CustomProfileCommand.custom("123",slot,minimum()));
            assertEquals(slot+4,parsed.displayIndex);assertEquals(minimum(),parsed.values);
        }
        String[] invalid={null,"","1:8","1:-1","1:04","1:4:700000","1:0:700000:1000000:1100000:403000000",
            "1:4:700000:1000000:1100000:9999999999","1:4:700000:1000000:1100000:403000000:0",
            "1:4:700000:1000000:1100000:403000001","1:4\n","a:4","123456789012345678901:4"};
        for(String input:invalid)reject(()->CustomProfileCommand.parse(input));
    }
    private static final class Memory implements CustomProfileStore.Persistence {
        CustomProfileStore.Snapshot value=new CustomProfileStore.Snapshot(2,"old",new String[]{null,null,null,null});
        int failures;
        @Override public CustomProfileStore.Snapshot read(){return value;}
        @Override public boolean write(CustomProfileStore.Snapshot next){value=next;if(failures>0){failures--;return false;}return true;}
    }
    @Test public void eachSlotPersistsIndependentlyAndDefaultPreservesTheBank() {
        Memory memory=new Memory();
        for(int slot=0;slot<4;slot++)CustomProfileStore.select(memory,slot+4,minimum(),"slot"+slot);
        CustomProfileStore.select(memory,0,null,null);
        assertEquals(0,memory.value.displayIndex);assertEquals("slot3",memory.value.lastRequest);
        for(int slot=0;slot<4;slot++)assertEquals(minimum().encode(),memory.value.slot(slot));
    }
    @Test public void failedCommitRollsBackMemoryBeforeFailureAcknowledgement() {
        Memory memory=new Memory();CustomProfileStore.select(memory,4,minimum(),"saved");
        memory.failures=2; // Both candidate and rollback update memory, but report disk failure.
        try{CustomProfileStore.select(memory,5,CustomProfileValues.small(),"failed");fail();}catch(IllegalStateException expected){}
        assertEquals(4,memory.value.displayIndex);assertEquals("saved",memory.value.lastRequest);
        assertEquals(minimum().encode(),memory.value.slot(0));assertNull(memory.value.slot(1));
        CustomProfileStore.acknowledge(memory,"failed");
        assertEquals(4,memory.value.displayIndex);assertEquals("failed",memory.value.lastRequest);assertNull(memory.value.slot(1));
    }
    @Test public void failedDefaultAndFailedAcknowledgementKeepThePreviousSelection() {
        Memory memory=new Memory();memory.failures=1;
        try{CustomProfileStore.select(memory,0,null,"default");fail();}catch(IllegalStateException expected){}
        assertEquals(2,memory.value.displayIndex);assertEquals("old",memory.value.lastRequest);
        memory.failures=1;
        try{CustomProfileStore.acknowledge(memory,"new");fail();}catch(IllegalStateException expected){}
        assertEquals("old",memory.value.lastRequest);
        reject(()->CustomProfileStore.select(memory,0,minimum(),null));
        reject(()->CustomProfileStore.select(memory,8,null,null));
    }
}
