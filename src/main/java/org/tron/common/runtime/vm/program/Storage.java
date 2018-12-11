package org.tron.common.runtime.vm.program;

import static java.lang.System.arraycopy;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.utils.PerformanceHelper;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.db.StorageRowStore;

public class Storage {

  @Getter
  private byte[] addrHash;
  @Getter
  private StorageRowStore store;
  @Getter
  private final Map<DataWord, StorageRowCapsule> rowCache = new HashMap<>();

  private static final int PREFIX_BYTES = 16;

  public Storage(byte[] address, StorageRowStore store) {
    addrHash = addrHash(address);
    this.store = store;
  }

  public Storage(Storage storage) {
    this.addrHash = storage.addrHash.clone();
    this.store = storage.store;
    storage.getRowCache().forEach((DataWord rowKey, StorageRowCapsule row) -> {
      StorageRowCapsule newRow = new StorageRowCapsule(row);
      this.rowCache.put(rowKey.clone(), newRow);
    });
  }

  public DataWord getValue(DataWord key) {
    if (rowCache.containsKey(key)) {
      long preMs = System.nanoTime() / 1000;
      DataWord value = rowCache.get(key).getValue();
      PerformanceHelper.singleTxOpcodeInfo.add("SLOAD_IN_CACHE\1" + String.valueOf(System.nanoTime() / 1000 - preMs));
      return value;
    } else {
      long preMs = System.nanoTime() / 1000;
      StorageRowCapsule row = store.get(compose(key.getData(), addrHash));
      if (row == null || row.getInstance() == null) {
        PerformanceHelper.singleTxOpcodeInfo.add("SLOAD_NOT_CACHE\1" + String.valueOf(System.nanoTime() / 1000 - preMs));
        return null;
      }
      rowCache.put(key, row);
      PerformanceHelper.singleTxOpcodeInfo.add("SLOAD_NOT_CACHE\1" + String.valueOf(System.nanoTime() / 1000 - preMs));
      return row.getValue();
    }
  }

  public void put(DataWord key, DataWord value) {
    if (rowCache.containsKey(key)) {
      rowCache.get(key).setValue(value);
    } else {
      byte[] rowKey = compose(key.getData(), addrHash);
      StorageRowCapsule row = new StorageRowCapsule(rowKey, value.getData());
      rowCache.put(key, row);
    }
  }

  private static byte[] compose(byte[] key, byte[] addrHash) {
    byte[] result = new byte[key.length];
    arraycopy(addrHash, 0, result, 0, PREFIX_BYTES);
    arraycopy(key, PREFIX_BYTES, result, PREFIX_BYTES, PREFIX_BYTES);
    return result;
  }

  // 32 bytes
  private static byte[] addrHash(byte[] address) {
    return Hash.sha3(address);
  }

  public void commit() {
    rowCache.forEach((DataWord rowKey, StorageRowCapsule row) -> {
      if (row.isDirty()) {
        if (row.getValue().isZero()) {
          this.store.delete(row.getRowKey());
        } else {
          this.store.put(row.getRowKey(), row);
        }
      }
    });
  }
}
