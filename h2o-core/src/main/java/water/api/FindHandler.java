package water.api;

import water.H2O;
import water.MRTask;
import water.api.schemas3.FindV3;
import water.api.schemas3.FrameV3;
import water.exceptions.H2OColumnNotFoundArgumentException;
import water.exceptions.H2OCategoricalLevelNotFoundArgumentException;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.*;
import water.util.ArrayUtils;
import water.util.IcedHashMap;
import water.util.IcedHashMapGeneric;

class FindHandler extends Handler {

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FindV3 find(int version, FindV3 find) {
    Frame frame = find.key._fr;
    // Peel out an optional column; restrict to this column
    if( find.column != null ) {
      VecAry vec = frame.vec(find.column);
      if( vec==null ) throw new H2OColumnNotFoundArgumentException("column", frame, find.column);
      find.key = new FrameV3(new Frame(new String[]{find.column}, vec));
    }

    // Convert the search string into a column-specific flavor
    VecAry vecs = frame.vecs();
    double ds[] = new double[vecs._numCols];
    for( int i=0; i<vecs._numCols; i++ ) {
      if( vecs.isCategorical(i) ) {
        int idx = ArrayUtils.find(vecs.domain(i),find.match);
        if( idx==-1 && vecs._numCols==1 ) throw new H2OCategoricalLevelNotFoundArgumentException("match", find.match, frame._key.toString(), frame.name(i));
        ds[i] = idx;
      } else if( vecs.isUUID(i) ) {
        throw H2O.unimpl();
      } else if( vecs.isString(i) ) {
        throw H2O.unimpl();
      } else if( vecs.isTime(i) ) {
        throw H2O.unimpl();
      } else {
        try {
          ds[i] = find.match==null ? Double.NaN : Double.parseDouble(find.match);
        } catch( NumberFormatException e ) {
          if( vecs._numCols==1 ) {
            // There's only one Vec and it's a numeric Vec and our search string isn't a number
            IcedHashMapGeneric.IcedHashMapStringObject values = new IcedHashMapGeneric.IcedHashMapStringObject();
            String msg = "Frame: " + frame._key.toString() + " as only one column, it is numeric, and the find pattern is not numeric: " + find.match;
            values.put("frame_name", frame._key.toString());
            values.put("column_name", frame.name(i));
            values.put("pattern", find.match);
            throw new H2OIllegalArgumentException(msg, msg, values);
          }
          ds[i] = Double.longBitsToDouble(0xcafebabe); // Do not match
        }
      }
    }

    Find f = new Find(find.row,ds).doAll(frame);
    find.prev = f._prev;
    find.next = f._next==Long.MAX_VALUE ? -1 : f._next;
    return find;
  }

  private static class Find extends MRTask<Find> {
    final long _row;
    final double[] _ds;
    long _prev, _next;
    Find( long row, double[] ds ) { 
      super((byte)(H2O.GUI_PRIORITY - 2));
      _row = row; _ds = ds; _prev = -1; _next = Long.MAX_VALUE; 
    }
    @Override public void map( ChunkAry cs ) {
      for( int col = 0; col<cs._numCols; col++ ) {
        for( int row=0; row<cs._len; row++ ) {
          if( cs.atd(row,col) == _ds[col] || (cs.isNA(row,col) && Double.isNaN(_ds[col])) ) {
            long r = cs._start+row;
            if( r < _row ) { if( r > _prev ) _prev = r; }
            else if( r > _row ) { if( r < _next ) _next = r; }
          }
        }
      }
    }
    @Override public void reduce( Find f ) {
      if( _prev < f._prev ) _prev = f._prev;
      if( _next > f._next ) _next = f._next;
    }
  }
}
