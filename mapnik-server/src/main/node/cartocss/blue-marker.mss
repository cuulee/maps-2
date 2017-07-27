/*
 * A blue style that paints the center of the polygon with a circle with the circle adjusting size depending on the
 * count.
 */

#occurrence {
  marker-fill: #209fff;
  marker-allow-overlap: true;
  marker-opacity: 0.65;
  line-color: "#ff0000";
  line-width: 0;
  marker-line-width: 0;
  line-opacity: 0.5;
}

#occurrence {
                  [total <=     10] { marker-width:  2; }
  [total >     10][total <=    100] { marker-width:  6; }
  [total >    100][total <=   1000] { marker-width:  8; }
  [total >   1000][total <=  10000] { marker-width: 10; }
  [total >  10000][total <= 100000] { marker-width: 12; }
  [total > 100000]                  { marker-width: 14; }
}
