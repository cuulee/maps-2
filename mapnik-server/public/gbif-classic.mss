#occurrence {
  dot-width: 2;
  dot-opacity: 1;
  [total <= 10] { dot-fill: #FFFF00;  }
  [total > 10][total <= 100] { dot-fill: #FFCC00;  }
  [total > 100][total <= 1000] { dot-fill: #FF9900;  }
  [total > 1000][total <= 10000] { dot-fill: #FF6600;  }
  [total > 10000][total <= 100000] { dot-fill: #FF3300;  }
}
